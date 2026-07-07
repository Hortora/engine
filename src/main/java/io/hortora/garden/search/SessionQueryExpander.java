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
        "You are a hypothetical document generator for a developer knowledge garden. "
            + "When given a question, write a short technical knowledge-base entry (3-5 sentences) "
            + "about Java, Quarkus, or software development that would directly answer it. "
            + "Write as if the entry comes from a curated developer knowledge garden. "
            + "Do not include the question itself. Do not use markdown formatting. "
            + "Respond with only the entry text.";

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

                LOG.fine(() -> "HyDE expansion: " + hypothetical.substring(0, Math.min(80, hypothetical.length())));
                return List.of(query.withExpansion(hypothetical));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "HyDE session query failed — using original query", e);
                resetSession();
                return List.of(query);
            }
        }
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
