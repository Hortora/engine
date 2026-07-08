package io.hortora.garden.search;

import io.casehub.neocortex.rag.QueryExpander;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.casehub.neocortex.rag.expansion.ExpansionConfig;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionInit;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
@IfBuildProperty(name = "casehub.rag.expansion.mode", stringValue = "session")
public class SessionQueryExpander implements QueryExpander {

    private static final Logger LOG = Logger.getLogger(SessionQueryExpander.class.getName());

    static final String SYSTEM_PROMPT =
        "You generate hypothetical knowledge-garden entries for semantic search. "
            + "The garden contains gotchas, techniques, undocumented behaviours, and architectural conventions "
            + "for JVM development (Java, Quarkus, CDI, JPA/Hibernate, Vert.x, reactive, Maven). "
            + "Given a query, write 1-2 sentences that a matching entry's title and root-cause summary would contain. "
            + "Use the same technical vocabulary the entry would use — class names, annotation names, exception names. "
            + "Do not explain, do not hedge, do not use markdown. "
            + "If the query is too vague to produce a specific entry, respond with exactly: SKIP";

    private final AgentProvider agentProvider;
    private final ExpansionConfig config;
    private final Object sessionLock = new Object();
    private volatile AgentSession session;

    @Inject
    public SessionQueryExpander(AgentProvider agentProvider, ExpansionConfig config) {
        this.agentProvider = agentProvider;
        this.config = config;
        LOG.info("SessionQueryExpander active — using AgentProvider session for HyDE");
    }

    @Override
    public List<RetrievalQuery> expand(RetrievalQuery query) {
        String promptTemplate = config.promptTemplate()
            .orElse("Question: %s");
        String userPrompt = promptTemplate.formatted(query.text());

        synchronized (sessionLock) {
            try {
                AgentSession s = getOrCreateSession();
                String hypothetical = s.query(userPrompt)
                    .filter(e -> e instanceof AgentEvent.TextDelta)
                    .map(e -> ((AgentEvent.TextDelta) e).text())
                    .collect().with(Collectors.joining())
                    .await().atMost(Duration.ofMinutes(2));

                if (hypothetical == null || hypothetical.isBlank()) {
                    LOG.warning("HyDE expansion returned empty — using original query");
                    return List.of(query);
                }

                String trimmed = hypothetical.strip();
                if (shouldSkip(trimmed)) {
                    LOG.fine(() -> "HyDE expansion skipped (low confidence) — using original query");
                    return List.of(query);
                }

                LOG.fine(() -> "HyDE expansion: " + trimmed.substring(0, Math.min(80, trimmed.length())));
                return List.of(query.withExpansion(trimmed));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "HyDE session query failed — using original query", e);
                resetSession();
                return List.of(query);
            }
        }
    }

    static boolean shouldSkip(String response) {
        if ("SKIP".equalsIgnoreCase(response)) return true;
        if (response.length() < 30) return true;
        String lower = response.toLowerCase();
        return lower.startsWith("i'm not sure")
            || lower.startsWith("i don't know")
            || lower.startsWith("it's unclear")
            || lower.startsWith("this is a broad");
    }

    private AgentSession getOrCreateSession() {
        if (session == null) {
            LOG.info("Opening AgentSession for HyDE expansion");
            session = agentProvider.openSession(AgentSessionInit.of(SYSTEM_PROMPT));
        }
        return session;
    }

    private void resetSession() {
        AgentSession s = session;
        session = null;
        if (s != null) {
            try {
                s.close(Duration.ofSeconds(5));
            } catch (Exception ignored) {
            }
        }
    }

    @PreDestroy
    void shutdown() {
        resetSession();
    }
}
