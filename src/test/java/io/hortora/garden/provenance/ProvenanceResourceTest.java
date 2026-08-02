package io.hortora.garden.provenance;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class ProvenanceResourceTest {

    @Inject ProvenanceStore store;

    @BeforeEach
    void clear() {
        store.deleteAll();
    }

    @Test
    void postRecordsProvenance() {
        given().contentType(ContentType.JSON)
                .body(new ProvenanceRecordRequest("Hortora/trellis", 14,
                        "spec.md", List.of("GE-0031", "GE-0045"), "brainstorming"))
                .when().post("/provenance")
                .then().statusCode(201)
                .body("recorded", equalTo(2));
    }

    @Test
    void forwardLineage() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031"), "brainstorming");

        given().queryParam("issueRepo", "Hortora/trellis")
                .queryParam("issueNumber", 14)
                .when().get("/provenance")
                .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].geId", equalTo("GE-0031"));
    }

    @Test
    void reverseLineage() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031"), "brainstorming");

        given().queryParam("geId", "GE-0031")
                .when().get("/provenance/reverse")
                .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].issueRepo", equalTo("Hortora/trellis"));
    }

    @Test
    void stats() {
        store.record("Hortora/trellis", 14, "", List.of("GE-0031", "GE-0045"), "brainstorming");

        given().when().get("/provenance/stats")
                .then().statusCode(200)
                .body("totalRecords", equalTo(2))
                .body("uniqueEntries", equalTo(2));
    }

    @Test
    void postEmptyGeIdsReturns400() {
        given().contentType(ContentType.JSON)
                .body(new ProvenanceRecordRequest("Hortora/trellis", 14,
                        "", List.of(), "brainstorming"))
                .when().post("/provenance")
                .then().statusCode(400);
    }

    @Test
    void postNullIssueRepoReturns400() {
        given().contentType(ContentType.JSON)
                .body(new ProvenanceRecordRequest(null, 14,
                        "", List.of("GE-0031"), "brainstorming"))
                .when().post("/provenance")
                .then().statusCode(400);
    }

    @Test
    void postCoercesNullSpecNameToEmpty() {
        given().contentType(ContentType.JSON)
                .body(new ProvenanceRecordRequest("Hortora/trellis", 14,
                        null, List.of("GE-0031"), "brainstorming"))
                .when().post("/provenance")
                .then().statusCode(201);

        List<ProvenanceRecord> lineage = store.forwardLineage("Hortora/trellis", 14);
        assertEquals("", lineage.getFirst().specName());
    }
}
