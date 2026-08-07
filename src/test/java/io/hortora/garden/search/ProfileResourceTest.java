package io.hortora.garden.search;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class ProfileResourceTest {

    @Test
    void putAndGetProfile() {
        given().contentType("application/json")
                .body("{\"stack\": \"quarkus:3.36.1|jdk:26.0.2\"}")
                .when().put("/api/garden/profiles/test-proj")
                .then().statusCode(204);

        given().when().get("/api/garden/profiles/test-proj")
                .then().statusCode(200)
                .body("name", is("test-proj"))
                .body("stack", containsString("quarkus:3.36.1"));
    }

    @Test
    void getNotFound() {
        given().when().get("/api/garden/profiles/nonexistent-xyzzy")
                .then().statusCode(404);
    }

    @Test
    void listProfiles() {
        given().contentType("application/json")
                .body("{\"stack\": \"jdk:21\"}")
                .when().put("/api/garden/profiles/list-test")
                .then().statusCode(204);

        given().when().get("/api/garden/profiles")
                .then().statusCode(200)
                .body("$", hasItem("list-test"));
    }

    @Test
    void deleteProfile() {
        given().contentType("application/json")
                .body("{\"stack\": \"jdk:21\"}")
                .when().put("/api/garden/profiles/to-delete")
                .then().statusCode(204);

        given().when().delete("/api/garden/profiles/to-delete")
                .then().statusCode(204);

        given().when().get("/api/garden/profiles/to-delete")
                .then().statusCode(404);
    }

    @Test
    void putWithoutStackReturns400() {
        given().contentType("application/json")
                .body("{}")
                .when().put("/api/garden/profiles/bad")
                .then().statusCode(400);
    }
}
