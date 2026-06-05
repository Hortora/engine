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
}
