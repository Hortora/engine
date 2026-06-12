package io.hortora.garden.federation;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(FederationIntegrationTest.FederationTestProfile.class)
class FederationIntegrationTest {

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().port(9999));
        wireMock.start();
        WireMock.configureFor("localhost", 9999);
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @Test
    void upstreamResultsMergedWithOwnResults() {
        wireMock.stubFor(get(urlPathEqualTo("/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            [
                                {
                                    "id": "upstream-entry-1",
                                    "title": "Upstream gotcha",
                                    "domain": "jvm",
                                    "type": "gotcha",
                                    "score": 10,
                                    "body": "Upstream body",
                                    "relevance": 0.85,
                                    "source": "upstream-garden",
                                    "sourcePrefix": "UG"
                                }
                            ]
                            """)));

        given()
                .queryParam("q", "hibernate lazy loading exception")
                .queryParam("limit", "10")
        .when()
                .get("/search")
        .then()
                .statusCode(200)
                .body("$", not(empty()))
                .body("source", hasItem("upstream-garden"))
                .body("source", hasItem("test-garden"));
    }

    @Test
    void visitedHeaderSentToUpstream() {
        wireMock.stubFor(get(urlPathEqualTo("/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        given()
                .queryParam("q", "test query")
        .when()
                .get("/search");

        wireMock.verify(getRequestedFor(urlPathEqualTo("/search"))
                .withHeader("X-Federation-Visited", containing("test-garden")));
    }

    @Test
    void upstreamTimeoutReturnsOwnResults() {
        wireMock.stubFor(get(urlPathEqualTo("/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(10000)
                        .withBody("[]")));

        given()
                .queryParam("q", "hibernate lazy loading exception")
        .when()
                .get("/search")
        .then()
                .statusCode(200)
                .body("$", not(empty()))
                .body("source", hasItem("test-garden"));
    }

    @Test
    void cycleDetectionReturnsEmpty() {
        given()
                .queryParam("q", "test query")
                .header("X-Federation-Visited", "test-garden")
        .when()
                .get("/search")
        .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    void depthExceededReturnsOwnResultsWithoutWalking() {
        // Visited header has more entries than max-depth (5) — should return own results only
        wireMock.stubFor(get(urlPathEqualTo("/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        given()
                .queryParam("q", "hibernate lazy loading exception")
                .header("X-Federation-Visited", "a,b,c,d,e,f")
        .when()
                .get("/search")
        .then()
                .statusCode(200)
                .body("$", not(empty()))
                .body("source", hasItem("test-garden"));

        // Upstream should NOT have been called — depth exceeded
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/search")));
    }

    @Test
    void ownResultsLabelledWithGardenId() {
        given()
                .queryParam("q", "hibernate lazy loading exception")
        .when()
                .get("/search")
        .then()
                .statusCode(200)
                .body("[0].source", equalTo("test-garden"))
                .body("[0].sourcePrefix", equalTo("TG"));
    }

    public static class FederationTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "hortora.garden.schema-path", "src/test/resources/fixtures/schema/federation-test.yaml",
                    "hortora.garden.id", "test-garden",
                    "hortora.garden.id-prefix", "TG"
            );
        }
    }
}
