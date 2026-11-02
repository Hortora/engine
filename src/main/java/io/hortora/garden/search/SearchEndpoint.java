package io.hortora.garden.search;

import io.casehub.neocortex.corpus.management.impl.AdaptiveResult;
import io.casehub.neocortex.corpus.management.impl.SearchResource;
import io.casehub.neocortex.corpus.management.impl.SearchResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/search")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class SearchEndpoint {

    @Inject SearchResource searchResource;

    @GET
    public List<SearchResult> search(
            @QueryParam("q") String query,
            @QueryParam("domain") List<String> domains,
            @QueryParam("type") String type,
            @QueryParam("tags") String tags,
            @QueryParam("limit") Integer limit,
            @HeaderParam("X-Federation-Visited") String visited) {
        requireQuery(query);
        return searchResource.search(query, domains, type, tags, limit, visited);
    }

    @GET
    @Path("/adaptive")
    public AdaptiveResult adaptiveSearch(
            @QueryParam("q") String query,
            @QueryParam("keywords") String keywords,
            @QueryParam("domain") List<String> domains,
            @QueryParam("type") String type,
            @QueryParam("tags") String tags,
            @QueryParam("limit") Integer limit) {
        requireQuery(query);
        return searchResource.adaptiveSearchEndpoint(query, keywords, domains, type, tags, limit);
    }

    private static void requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new WebApplicationException("query parameter 'q' is required", Response.Status.BAD_REQUEST);
        }
    }
}
