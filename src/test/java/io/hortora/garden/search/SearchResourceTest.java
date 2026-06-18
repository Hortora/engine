package io.hortora.garden.search;

import io.casehub.rag.PayloadFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;

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
}
