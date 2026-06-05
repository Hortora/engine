package io.hortora.garden.search;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class SearchResourceTest {

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
            .queryParam("q", "hibernate lazy loading exception")
        .when()
            .get("/search")
        .then()
            .statusCode(200)
            .contentType("application/json")
            .body("$", instanceOf(java.util.List.class));
    }

    @Test
    void limitIsRespected() {
        given()
            .queryParam("q", "exception handling")
            .queryParam("limit", "1")
        .when()
            .get("/search")
        .then()
            .statusCode(200)
            .body("$", hasSize(lessThanOrEqualTo(1)));
    }

    @Test
    void domainFilterReturnsOnlyMatchingDomain() {
        // Note: the limit-correctness motivation for this filter (Qdrant pre-filter ensures
        // `limit` is respected when domain entries are sparse in the corpus) cannot be
        // demonstrated with the in-memory TestEmbeddingStore — it lacks the "wasted slots"
        // problem that motivated moving the filter server-side. These tests verify correct
        // domain isolation; limit-correctness is a Qdrant-specific guarantee.
        given()
            .queryParam("q", "lazy loading transaction boundary")
            .queryParam("domain", "jvm")
        .when()
            .get("/search")
        .then()
            .statusCode(200)
            .body("$", not(empty()))
            // REST Assured GPath returns null (not []) when findAll yields no results
            .body("$.findAll { it.domain != 'jvm' }", anyOf(nullValue(), hasSize(0)));
    }

    @Test
    void domainFilterExcludesOtherDomains() {
        given()
            .queryParam("q", "collection metadata cache")
            .queryParam("domain", "tools")
        .when()
            .get("/search")
        .then()
            .statusCode(200)
            .body("$", not(empty()))
            // REST Assured GPath returns null (not []) when findAll yields no results
            .body("$.findAll { it.domain == 'jvm' }", anyOf(nullValue(), hasSize(0)));
    }
}
