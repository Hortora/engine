package io.hortora.garden.federation;

import io.hortora.garden.search.SearchResult;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Consumes(MediaType.APPLICATION_JSON)
interface RemoteGardenClient {

    @GET
    @Path("/search")
    List<SearchResult> search(
            @QueryParam("q") String query,
            @QueryParam("domain") List<String> domains,
            @QueryParam("limit") int limit,
            @HeaderParam("X-Federation-Visited") String visited
    );
}
