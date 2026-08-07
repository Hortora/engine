package io.hortora.garden.search;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.Optional;

@Path("/api/garden/profiles")
@Produces(MediaType.APPLICATION_JSON)
public class ProfileResource {

    @Inject
    SearchProfileStore store;

    @PUT
    @Path("/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response put(@PathParam("name") String name, Map<String, String> body) {
        String stack = body.get("stack");
        if (stack == null || stack.isBlank()) {
            return Response.status(400).entity(Map.of("error", "stack is required")).build();
        }
        store.put(name, stack);
        return Response.noContent().build();
    }

    @GET
    @Path("/{name}")
    public Response get(@PathParam("name") String name) {
        String rawStack = store.getRawStack(name);
        if (rawStack == null) return Response.status(404).build();
        return Response.ok(Map.of("name", name, "stack", rawStack)).build();
    }

    @GET
    public Response list() {
        return Response.ok(store.list()).build();
    }

    @DELETE
    @Path("/{name}")
    public Response delete(@PathParam("name") String name) {
        store.delete(name);
        return Response.noContent().build();
    }
}
