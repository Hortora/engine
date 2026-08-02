package io.hortora.garden.mcp;

import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.EmbeddingIngestor;
import io.casehub.neocortex.rag.RetrievalTracker;
import io.hortora.garden.config.GardenConfig;
import io.hortora.garden.federation.FederationConfig;
import io.hortora.garden.inference.CollectionMigration;
import io.hortora.garden.search.AdaptiveResult;
import io.hortora.garden.search.SearchResource;
import io.hortora.garden.search.SearchResult;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class GardenMcpTools {

    @Inject
    SearchResource      searchResource;
    @Inject
    EmbeddingIngestor   embeddingIngestor;
    @Inject
    GardenConfig        config;
    @Inject
    FederationConfig    federationConfig;
    @Inject
    CollectionMigration collectionMigration;
    @Inject
    RetrievalTracker retrievalTracker;
    @Inject
    io.hortora.garden.provenance.ProvenanceStore provenanceStore;

    private volatile java.util.Map<String, String> cachedGeIdToDocId;
    private volatile long cachedGeIdToDocIdTimestamp;
    private static final long DOC_CACHE_TTL_MS = 300_000;
    private static final java.nio.file.Path SHADOW_LOG = java.nio.file.Path.of(
            System.getProperty("hortora.shadow.log",
                               System.getProperty("user.home") + "/.hortora/logs/rag-comparison.jsonl"));


    static boolean passesMinDaysFilter(String documentId, int minDays) {
        String filename = documentId.contains("/")
                          ? documentId.substring(documentId.lastIndexOf('/') + 1) : documentId;
        if (filename.matches("GE-\\d{8}-[0-9a-f]{6}\\.md")) {
            String dateStr = filename.substring(3, 11);
            try {
                LocalDate entryDate = LocalDate.parse(dateStr, DateTimeFormatter.BASIC_ISO_DATE);
                return ChronoUnit.DAYS.between(entryDate, LocalDate.now()) >= minDays;
            } catch (DateTimeParseException e) {
                return true;
            }
        }
        return true;
    }

    private static Map<String, List<String>> groupByDomain(List<String> documentIds) {
        return documentIds.stream()
                          .collect(Collectors.groupingBy(
                                  id -> id.contains("/") ? id.substring(0, id.indexOf('/')) : "unknown",
                                  TreeMap::new, Collectors.toList()));
    }

    public static String extractDocumentId(String path) {
        if (path == null) {
            return "";
        }
        String withoutExt = path.replaceFirst("\\.md$", "");
        String filename = withoutExt.contains("/")
                          ? withoutExt.substring(withoutExt.lastIndexOf('/') + 1)
                          : withoutExt;
        if (filename.matches("GE-\\d{8}-[0-9a-f]{6}")) {
            return filename;
        }
        return withoutExt;
    }

    public static String stripTitlePrefix(String title, String body) {
        if (title != null && body != null && body.startsWith(title + "\n\n")) {
            return body.substring(title.length() + 2);
        }
        return body;
    }

    @Tool(description = "Search the Hortora knowledge garden for relevant entries about non-obvious developer knowledge, gotchas, techniques, and undocumented behaviours. Returns full entry content for LLM consumption. Results are adaptively extended when a dense cluster of relevant entries exists beyond the requested limit. Best practice: combine a natural-language query describing the problem with pipe-separated keywords naming the specific classes, methods, or config keys involved. The NL query finds semantically similar entries; the keywords ensure exact-match entries are not missed. Omitting keywords when you know the specific terms typically misses 30-50% of relevant entries.")
    String gardenSearch(
            @ToolArg(description = "Natural language description of the problem, symptom, or topic to search for") String query,
            @ToolArg(description = "Pipe-separated technical terms (class names, method names, config keys, error messages) that boost exact-match recall via BM25. ALWAYS provide when the query involves specific APIs, classes, or error messages — omitting keywords typically misses 30-50% of relevant entries. Example: 'QuarkusTestProfile|getConfigOverrides|selected-alternatives'.", required = false) String keywords,
            @ToolArg(description = "Optional: filter by domain (e.g. jvm, tools, python). Leave empty to search all domains.", required = false) String domain,
            @ToolArg(description = "Optional: filter by entry type (gotcha, technique, undocumented, pattern)", required = false) String type,
            @ToolArg(description = "Optional: comma-separated tags to filter by (entries matching ANY tag are returned)", required = false) String tags,
            @ToolArg(description = "Maximum number of entries to return (default 16, max 50). May return more if a dense cluster of relevant results exists beyond this limit.", required = false) Integer limit) {
        String expandedKeywords = keywords != null && !keywords.isBlank()
                                  ? keywords.replace("|", " ")
                                  : null;

        AdaptiveResult adaptive;
        long           latencyMs;
        try {
            long start = System.nanoTime();
            adaptive  = searchResource.searchAdaptive(query, expandedKeywords,
                                                      domain != null && !domain.isBlank() ? List.of(domain) : null,
                                                      type, tags, limit);
            latencyMs = (System.nanoTime() - start) / 1_000_000;
        } catch (Exception e) {
            Log.warn("gardenSearch failed — Qdrant may be unavailable", e);
            return "Garden search unavailable — Qdrant is not responding. "
                   + "Start Qdrant and restart the engine (scripts/update-engine.sh update). "
                   + "Query was: " + query;
        }

        logSearch(query, keywords, domain, type, tags, limit, adaptive, latencyMs);

        if (adaptive.results().isEmpty()) {
            return "No relevant garden entries found for: " + query;
        }

        List<SearchResult> expandedResults = expandWithSeeAlso(adaptive.results(), query, domain);

        StringBuilder sb = new StringBuilder();

        sb.append("<!-- search_meta: returned=").append(adaptive.results().size())
          .append(" available=").append(adaptive.availableAboveFloor())
          .append(" requested=").append(adaptive.requestedLimit())
          .append(" extended=").append(adaptive.extended())
          .append(" trimmed=").append(adaptive.trimmed())
          .append(" floor_filtered=").append(adaptive.floorFiltered())
          .append(" -->\n");

        if (adaptive.trimmed()) {
            sb.append("*Showing ").append(adaptive.results().size())
              .append(" results (").append(adaptive.requestedLimit())
              .append(" requested, trimmed at score gap).*\n");
        } else if (adaptive.extended() || adaptive.availableAboveFloor() > adaptive.results().size()) {
            sb.append("*Showing ").append(adaptive.results().size())
              .append(" results (").append(adaptive.requestedLimit()).append(" requested");
            if (adaptive.availableAboveFloor() > adaptive.results().size()) {
                sb.append(", ").append(adaptive.availableAboveFloor())
                  .append(" above relevance threshold in corpus");
            }
            sb.append("). Use a higher limit to see more.*\n");
        }

        sb.append("\n");

        sb.append(expandedResults.stream()
                          .map(r -> "## " + provenanceLabel(r) + " " + r.title()
                                    + "\n**ID:** " + extractDocumentId(r.id())
                                    + " · **Domain:** " + r.domain()
                                    + " · **Type:** " + r.type()
                                    + " · " + (r.crossEncoderScore() != null
                                               ? "**Score:** " + String.format("%.1f", r.crossEncoderScore()) + " (CE)"
                                               : "**Relevance:** " + String.format("%.2f", r.relevance()))
                                    + "\n\n" + stripTitlePrefix(r.title(), r.body()))
                          .collect(Collectors.joining("\n\n---\n\n")));

        return sb.toString();
    }

    @Tool(description = "Get the status of the garden index: how many entries are indexed and where the garden is located.")
    String gardenStatus() {
        CorpusRef corpusRef = new CorpusRef("hortora", config.id());
        int       count;
        try {
            count = embeddingIngestor.listDocuments(corpusRef).size();
        } catch (Exception e) {
            Log.warn("Failed to count indexed entries", e);
            return "Garden path: " + config.path()
                   + "\nQdrant status: UNAVAILABLE — not responding. "
                   + "Start Qdrant and restart the engine (scripts/update-engine.sh update).";
        }
        return "Garden path: " + config.path() + "\nIndexed entries: " + count;
    }

    @Tool(description = "Trigger a full re-index of the garden corpus. Deletes the current Qdrant collection and resets the cursor so the next ingestion cycle re-embeds all entries. Use after bulk metadata changes, reclassification, or schema evolution.")
    String gardenReindex() {
        CorpusRef corpusRef = new CorpusRef("hortora", config.id());
        int       fileCount;
        try {
            fileCount = embeddingIngestor.listDocuments(corpusRef).size();
        } catch (Exception e) {
            fileCount = -1;
        }

        try {
            collectionMigration.resetCorpus(corpusRef, config.id());
        } catch (Exception e) {
            Log.warn("Failed to trigger reindex", e);
            return "Reindex failed for garden '" + config.id() + "': " + e.getMessage();
        }

        return "Reindex triggered for garden '" + config.id()
               + "'. Collection deleted, cursor reset. Re-embedding will complete on next ingestion cycle"
               + (fileCount >= 0 ? " (" + fileCount + " entries in corpus)." : ".");
    }

    @Tool(description = "List garden entries not retrieved within the tracking window, or stale-retrieved. Retrieval records are retained for a configurable period (default 180 days); 'unretrieved' means no retrieval record exists in that window. Use to identify candidates for review or erasure during harvest sessions.")
    String gardenUnretrieved(
            @ToolArg(description = "Minimum age in days — entries indexed less than this many days ago are excluded (default 30)", required = false)
            Integer minDays,
            @ToolArg(description = "Stale threshold in days — entries retrieved at some point but not within this window are flagged as stale (default 90). Must be less than the retention period.", required = false)
            Integer staleDays) {

        int effectiveMinDays   = minDays != null && minDays > 0 ? minDays : 30;
        int effectiveStaleDays = staleDays != null && staleDays >= 0 ? staleDays : 90;

        CorpusRef corpusRef = new CorpusRef("hortora", config.id());

        List<String> allDocuments = embeddingIngestor.listDocuments(corpusRef);
        Set<String> everRetrieved = retrievalTracker.findRetrievedDocumentIds(
                corpusRef, Instant.EPOCH, Instant.now());

        List<String> unretrieved = allDocuments.stream()
                                               .filter(id -> !everRetrieved.contains(id))
                                               .filter(id -> passesMinDaysFilter(id, effectiveMinDays))
                                               .sorted()
                                               .toList();

        Set<String> recentlyRetrieved = retrievalTracker.findRetrievedDocumentIds(
                corpusRef,
                Instant.now().minus(effectiveStaleDays, ChronoUnit.DAYS),
                Instant.now());
        List<String> stale = allDocuments.stream()
                                         .filter(everRetrieved::contains)
                                         .filter(id -> !recentlyRetrieved.contains(id))
                                         .sorted()
                                         .toList();

        if (unretrieved.isEmpty() && stale.isEmpty()) {
            return "All " + allDocuments.size() + " entries have been retrieved within the tracking window.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Tracking window: retrieval records retained for configured period. ")
          .append("Stale threshold: ").append(effectiveStaleDays).append(" days.\n\n");

        if (!unretrieved.isEmpty()) {
            sb.append("## Unretrieved entries (").append(unretrieved.size()).append(")\n\n");
            Map<String, List<String>> byDomain = groupByDomain(unretrieved);
            for (var entry : byDomain.entrySet()) {
                sb.append("### ").append(entry.getKey()).append("\n");
                entry.getValue().forEach(id -> sb.append("- ").append(extractDocumentId(id)).append("\n"));
                sb.append("\n");
            }
        }

        if (!stale.isEmpty()) {
            sb.append("## Stale entries (").append(stale.size())
              .append(") — not retrieved in the last ").append(effectiveStaleDays).append(" days\n\n");
            Map<String, List<String>> byDomain = groupByDomain(stale);
            for (var entry : byDomain.entrySet()) {
                sb.append("### ").append(entry.getKey()).append("\n");
                entry.getValue().forEach(id -> sb.append("- ").append(extractDocumentId(id)).append("\n"));
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    @Tool(description = "Record which garden entries informed a design artifact. "
                        + "Call after the user selects relevant entries during brainstorming or work-start. "
                        + "Idempotent — re-recording the same provenance is a no-op.")
    String gardenRecordProvenance(
            @ToolArg(description = "GitHub repo (e.g. 'Hortora/trellis')") String issueRepo,
            @ToolArg(description = "Issue number") int issueNumber,
            @ToolArg(description = "Spec filename, if known (e.g. '2026-08-02-design.md'). "
                                   + "Pass empty string or omit when no spec exists yet.",
                     required = false) String specName,
            @ToolArg(description = "Pipe-separated GE-IDs (e.g. 'GE-0031|GE-20260618-c552c3')")
            String geIds,
            @ToolArg(description = "Source skill (e.g. 'brainstorming', 'work-start')",
                     required = false) String recordedBy) {

        List<String> ids = java.util.Arrays.stream(geIds.split("\\|"))
                                           .map(String::trim)
                                           .filter(s -> !s.isEmpty())
                                           .toList();

        if (ids.isEmpty()) {
            return "Error: no valid GE-IDs provided after filtering empty segments.";
        }

        String effectiveSpecName = (specName == null || specName.isBlank()) ? "" : specName;

        try {
            int count = provenanceStore.record(issueRepo, issueNumber, effectiveSpecName, ids, recordedBy);
            return "Recorded " + count + " provenance link(s) for " + issueRepo + "#" + issueNumber
                   + (effectiveSpecName.isEmpty() ? "" : " (spec: " + effectiveSpecName + ")")
                   + ": " + String.join(", ", ids);
        } catch (Exception e) {
            Log.warn("gardenRecordProvenance failed", e);
            return "Error recording provenance: " + e.getMessage();
        }
    }


    private java.util.Map<String, String> getGeIdToDocIdMap() {
        long now = System.currentTimeMillis();
        if (cachedGeIdToDocId != null && (now - cachedGeIdToDocIdTimestamp) < DOC_CACHE_TTL_MS) {
            return cachedGeIdToDocId;
        }
        CorpusRef                     corpusRef = new CorpusRef("hortora", config.id());
        List<String>                  allDocs   = embeddingIngestor.listDocuments(corpusRef);
        java.util.Map<String, String> map       = new java.util.HashMap<>();
        for (String docId : allDocs) {
            map.put(extractDocumentId(docId), docId);
        }
        cachedGeIdToDocId          = map;
        cachedGeIdToDocIdTimestamp = now;
        return map;
    }

    private List<SearchResult> expandWithSeeAlso(List<SearchResult> results, String query, String domain) {
        return SearchResource.expandWithAdjacent(results, missingIds -> {
            try {
                java.util.Map<String, String> geIdToDocId = getGeIdToDocIdMap();

                List<String> resolvedDocIds = new java.util.ArrayList<>();
                for (String geId : missingIds) {
                    String docId = geIdToDocId.get(geId);
                    if (docId != null) {resolvedDocIds.add(docId);}
                }

                if (resolvedDocIds.isEmpty()) {return List.of();}

                return searchResource.fetchByDocumentIds(query, resolvedDocIds);
            } catch (Exception e) {
                Log.debug("See-also expansion failed", e);
                return List.of();
            }
        });
    }

    private static final Object SHADOW_LOG_LOCK = new Object();

    private void logSearch(String query, String keywords, String domain, String type, String tags, Integer limit,
                           AdaptiveResult adaptive, long latencyMs) {
        try {
            var sb = new StringBuilder("{");
            sb.append("\"timestamp\":\"").append(Instant.now()).append("\",");
            sb.append("\"source\":\"mcp\",");
            sb.append("\"query\":").append(jsonString(query)).append(",");
            sb.append("\"keywords\":").append(jsonString(keywords)).append(",");
            if (domain != null && !domain.isBlank()) {sb.append("\"domain\":").append(jsonString(domain)).append(",");}
            if (type != null && !type.isBlank()) {sb.append("\"type\":").append(jsonString(type)).append(",");}
            if (tags != null && !tags.isBlank()) {sb.append("\"tags\":").append(jsonString(tags)).append(",");}
            sb.append("\"limit\":").append(limit != null ? limit : "null").append(",");
            sb.append("\"result_count\":").append(adaptive.results().size()).append(",");
            sb.append("\"extended\":").append(adaptive.extended()).append(",");
            sb.append("\"trimmed\":").append(adaptive.trimmed()).append(",");
            sb.append("\"available_above_floor\":").append(adaptive.availableAboveFloor()).append(",");
            sb.append("\"latency_ms\":").append(latencyMs).append(",");
            sb.append("\"results\":[");
            for (int i = 0; i < adaptive.results().size(); i++) {
                var r = adaptive.results().get(i);
                if (i > 0) {sb.append(",");}
                sb.append("{\"id\":").append(jsonString(extractDocumentId(r.id())));
                sb.append(",\"title\":").append(jsonString(r.title()));
                sb.append(",\"relevance\":").append(String.format("%.4f", r.relevance()));
                if (r.crossEncoderScore() != null) {
                    sb.append(",\"crossEncoderScore\":").append(String.format("%.4f", r.crossEncoderScore()));
                }
                sb.append("}");
            }
            sb.append("]}");

            String line = sb.toString() + System.lineSeparator();
            java.nio.file.Files.createDirectories(SHADOW_LOG.getParent());
            synchronized (SHADOW_LOG_LOCK) {
                java.nio.file.Files.writeString(SHADOW_LOG, line,
                                                java.nio.file.StandardOpenOption.CREATE,
                                                java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            Log.debug("Shadow log write failed", e);
        }
    }

    private static String jsonString(String value) {
        if (value == null) {return "null";}
        return "\"" + value.replace("\\", "\\\\")
                           .replace("\"", "\\\"")
                           .replace("\n", "\\n")
                           .replace("\r", "\\r")
                           .replace("\t", "\\t") + "\"";
    }

    private String provenanceLabel(SearchResult result) {
        if (federationConfig.gardenId().equals(result.source())) {
            return "[own]";
        }
        return "[" + result.sourcePrefix() + "]";
    }
}
