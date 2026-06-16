package io.hortora.garden.search;

import io.hortora.garden.test.QdrantResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@QuarkusTestResource(QdrantResource.class)
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
        // Qdrant pre-filter ensures `limit` is respected when domain entries are sparse
        // in the corpus. These tests verify correct domain isolation via the real Qdrant
        // instance provided by QdrantResource.
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

    @Test
    void multiDomainFilterReturnsBothDomains() {
        // With 2 fixture entries (jvm + tools), ?domain=jvm&domain=tools must return both
        given()
            .queryParam("q", "developer gotchas")
            .queryParam("domain", "jvm")
            .queryParam("domain", "tools")
        .when()
            .get("/search")
        .then()
            .statusCode(200)
            .body("$", hasSize(2));
    }

    @Test
    void multiDomainFilterExcludesUnrequestedDomain() {
        // Only jvm + nonexistent requested — tools entry must not appear
        given()
            .queryParam("q", "developer gotchas")
            .queryParam("domain", "jvm")
            .queryParam("domain", "nonexistent")
        .when()
            .get("/search")
        .then()
            .statusCode(200)
            .body("$", hasSize(1))
            .body("[0].domain", equalTo("jvm"));
    }

    @Test
    void searchResultsIncludeProvenanceFields() {
        given()
            .queryParam("q", "hibernate lazy loading exception")
        .when()
            .get("/search")
        .then()
            .statusCode(200)
            .body("$", not(empty()))
            .body("[0].source", equalTo("garden"))
            .body("[0].sourcePrefix", equalTo("GE"));
    }
}
