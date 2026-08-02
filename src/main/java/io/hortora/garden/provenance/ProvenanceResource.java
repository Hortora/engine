package io.hortora.garden.provenance;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/provenance")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class ProvenanceResource {

    @Inject ProvenanceStore store;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response record(ProvenanceRecordRequest request) {
        if (request.issueRepo() == null || request.issueRepo().isBlank()) {
            return Response.status(400).entity("{\"error\":\"issueRepo is required\"}").build();
        }
        if (request.geIds() == null || request.geIds().isEmpty()) {
            return Response.status(400).entity("{\"error\":\"geIds must not be empty\"}").build();
        }
        String specName = request.specName() != null ? request.specName() : "";
        int count = store.record(request.issueRepo(), request.issueNumber(),
                specName, request.geIds(), request.recordedBy());
        return Response.status(201).entity("{\"recorded\":" + count + "}").build();
    }

    @GET
    public List<ProvenanceRecord> forwardLineage(
            @QueryParam("issueRepo") String issueRepo,
            @QueryParam("issueNumber") int issueNumber) {
        return store.forwardLineage(issueRepo, issueNumber);
    }

    @GET
    @Path("/reverse")
    public List<ProvenanceRecord> reverseLineage(@QueryParam("geId") String geId) {
        return store.reverseLineage(geId);
    }

    @GET
    @Path("/stats")
    public ProvenanceStats stats() {
        return store.stats();
    }
}
