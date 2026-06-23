package io.hortora.garden.search;

import io.casehub.rag.ChunkInput;
import io.casehub.rag.CorpusRef;
import io.casehub.rag.PayloadFilter;
import io.casehub.rag.testing.InMemoryEmbeddingIngestor;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class SearchResourceTest {

    @Inject SearchResource searchResource;
    @Inject InMemoryEmbeddingIngestor ingestor;

    private static final CorpusRef CORPUS = new CorpusRef("hortora", "garden");

    @BeforeEach
    void seedFixtures() {
        ingestor.deleteCorpus(CORPUS);
        ingestor.ingest(CORPUS, List.of(
                new ChunkInput(
                        "Hibernate lazy loading fails outside transaction boundary.\n\nLazyInitializationException is thrown.",
                        "jvm/ge-test-hibernate-lazy.md",
                        Map.of("title", "Hibernate lazy loading fails outside transaction",
                                "domain", "jvm", "type", "gotcha", "score", "8")),
                new ChunkInput(
                        "Git stash metadata is lost when applying across branches.",
                        "tools/ge-test-git-stash.md",
                        Map.of("title", "Git stash metadata lost across branches",
                                "domain", "tools", "type", "gotcha", "score", "6"))
        ));
    }

    @Test
    void missingQueryReturns400() {
        given()
        .when()
            .get("/search")
        .then()
            .statusCode(400);
    }

    @Test
    void blankQueryReturns400() {
        given()
            .queryParam("q", "   ")
        .when()
            .get("/search")
        .then()
            .statusCode(400);
    }

    @Test
    void searchReturnsJsonArray() {
        given()
            .queryParam("q", "test query")
        .when()
            .get("/search")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("$", instanceOf(java.util.List.class));
    }

    @Test
    void buildDomainFilter_nullDomains() {
        assertThat(SearchResource.buildDomainFilter(null)).isNull();
    }

    @Test
    void buildDomainFilter_emptyDomains() {
        assertThat(SearchResource.buildDomainFilter(List.of())).isNull();
    }

    @Test
    void buildDomainFilter_singleDomain() {
        PayloadFilter filter = SearchResource.buildDomainFilter(List.of("jvm"));
        assertThat(filter).isInstanceOf(PayloadFilter.Eq.class);
        PayloadFilter.Eq eq = (PayloadFilter.Eq) filter;
        assertThat(eq.field()).isEqualTo("domain");
        assertThat(eq.value()).isEqualTo("jvm");
    }

    @Test
    void buildDomainFilter_multipleDomains() {
        PayloadFilter filter = SearchResource.buildDomainFilter(List.of("jvm", "tools"));
        assertThat(filter).isInstanceOf(PayloadFilter.In.class);
        PayloadFilter.In in = (PayloadFilter.In) filter;
        assertThat(in.field()).isEqualTo("domain");
        assertThat(in.values()).containsExactly("jvm", "tools");
    }

    @Test
    void buildDomainFilter_blankDomainsFiltered() {
        PayloadFilter filter = SearchResource.buildDomainFilter(List.of("", "  ", "jvm"));
        assertThat(filter).isInstanceOf(PayloadFilter.Eq.class);
        PayloadFilter.Eq eq = (PayloadFilter.Eq) filter;
        assertThat(eq.value()).isEqualTo("jvm");
    }

    @Test
    void parseVisitedTrimsWhitespace() {
        given()
            .queryParam("q", "test query")
            .header("X-Federation-Visited", "garden")
        .when()
            .get("/search")
        .then()
            .statusCode(200)
            .body("$", hasSize(0));

        // With spaces — should still detect cycle
        given()
            .queryParam("q", "test query")
            .header("X-Federation-Visited", " garden , other-garden ")
        .when()
            .get("/search")
        .then()
            .statusCode(200)
            .body("$", hasSize(0));
    }

    @Test
    void limitCappedAtMaximum() {
        List<SearchResult> results = searchResource.searchFor("test", null, 99999);
        assertThat(results).hasSizeLessThanOrEqualTo(SearchResource.MAX_LIMIT);
    }

    @Test
    void searchForReturnsResults() {
        List<SearchResult> results = searchResource.searchFor("hibernate lazy", null, null);
        assertThat(results).isNotEmpty();
    }

    @Test
    void domainFilterReturnsOnlyMatchingDomain() {
        given()
            .queryParam("q", "test query")
            .queryParam("domain", "jvm")
        .when()
            .get("/search")
        .then()
            .statusCode(200)
            .body("domain", everyItem(equalTo("jvm")));
    }
}
