package io.hortora.garden.search;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EntryResourceTest {


    @Test
    void getEntryByGeId() {
        given().when().get("/entries/GE-0031")
               .then().statusCode(200)
               .body("id", equalTo("GE-0031"))
               .body("domain", equalTo("jvm"))
               .body("title", equalTo("Hibernate lazy loading gotcha"))
               .body("score", equalTo(12));
    }

    @Test
    void getEntryNotFound() {
        given().when().get("/entries/GE-NONEXISTENT")
               .then().statusCode(404);
    }

    @Test
    void parseEntryExtractsMetadata() {
        String content = """
                         ---
                         id: GE-0031
                         title: "Test title"
                         type: gotcha
                         domain: jvm
                         score: 8
                         ---
                         
                         ## Test title
                         
                         Body content here.
                         """;
        EntryDetail detail = EntryResource.parseEntry("GE-0031", content);
        assertEquals("Test title", detail.title());
        assertEquals("jvm", detail.domain());
        assertEquals("gotcha", detail.type());
        assertEquals(8, detail.score());
        assertTrue(detail.body().contains("Body content here."));
    }

    @Test
    void normalizeGeIdStripsPathAndExtension() {
        assertEquals("GE-20260424-a02588", EntryResource.normalizeGeId("jvm/GE-20260424-a02588.md"));
        assertEquals("GE-0031", EntryResource.normalizeGeId("GE-0031"));
        assertEquals("GE-0031", EntryResource.normalizeGeId("quarkus/GE-0031.md"));
    }

}
