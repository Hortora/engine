package io.hortora.garden.index;

import io.casehub.neocortex.rag.ChunkInput;
import io.casehub.neocortex.rag.CorpusRef;
import io.casehub.neocortex.rag.testing.InMemoryEmbeddingIngestor;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ReindexResourceTest {

    @Inject InMemoryEmbeddingIngestor ingestor;

    private static final CorpusRef CORPUS = new CorpusRef("hortora", "garden");

    @BeforeEach
    void seedFixtures() {
        ingestor.deleteCorpus(CORPUS);
        ingestor.ingest(CORPUS, List.of(
                new ChunkInput(
                        "Test entry for reindex.",
                        "jvm/ge-test-reindex.md",
                        Map.of("title", "Test reindex entry"),
                        Map.of())));
    }

    @Test
    void postReindexReturnsOkStatus() {
        given()
            .when()
                .post("/api/garden/reindex")
            .then()
                .statusCode(200)
                .body("status", equalTo("ok"))
                .body("message", org.hamcrest.Matchers.containsString("Reindex triggered"));
    }

    @Test
    void postReindexClearsCorpus() {
        assertThat(ingestor.listDocuments(CORPUS)).isNotEmpty();

        given()
            .when()
                .post("/api/garden/reindex")
            .then()
                .statusCode(200);

        assertThat(ingestor.listDocuments(CORPUS)).isEmpty();
    }
}
