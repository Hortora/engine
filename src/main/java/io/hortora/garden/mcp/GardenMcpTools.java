package io.hortora.garden.mcp;

import io.casehub.rag.CorpusRef;
import io.casehub.rag.EmbeddingIngestor;
import io.hortora.garden.config.GardenConfig;
import io.hortora.garden.federation.FederationConfig;
import io.hortora.garden.search.SearchResource;
import io.hortora.garden.search.SearchResult;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class GardenMcpTools {

    @Inject SearchResource searchResource;
    @Inject EmbeddingIngestor embeddingIngestor;
    @Inject GardenConfig config;
    @Inject FederationConfig federationConfig;

    @Tool(description = "Search the Hortora knowledge garden for relevant entries about non-obvious developer knowledge, gotchas, techniques, and undocumented behaviours. Returns full entry content for LLM consumption.")
    String gardenSearch(
            @ToolArg(description = "Natural language description of the problem, symptom, or topic to search for") String query,
            @ToolArg(description = "Optional: filter by domain (e.g. jvm, tools, python). Leave empty to search all domains.", required = false) String domain,
            @ToolArg(description = "Maximum number of entries to return (default 8)", required = false) Integer limit) {

        List<SearchResult> results = searchResource.searchFor(query,
                domain != null && !domain.isBlank() ? List.of(domain) : null, limit);

        if (results.isEmpty()) {
            return "No relevant garden entries found for: " + query;
        }

        return results.stream()
                .map(r -> "## " + provenanceLabel(r) + " " + r.title() + "\n\n" + r.body())
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    @Tool(description = "Get the status of the garden index: how many entries are indexed and where the garden is located.")
    String gardenStatus() {
        CorpusRef corpusRef = new CorpusRef("hortora", config.id());
        int count;
        try {
            count = embeddingIngestor.listDocuments(corpusRef).size();
        } catch (Exception e) {
            Log.warn("Failed to count indexed entries", e);
            count = -1;
        }
        return "Garden path: " + config.path() + "\nIndexed entries: " + count;
    }

    private String provenanceLabel(SearchResult result) {
        if (federationConfig.gardenId().equals(result.source())) {
            return "[own]";
        }
        return "[" + result.sourcePrefix() + "]";
    }
}
