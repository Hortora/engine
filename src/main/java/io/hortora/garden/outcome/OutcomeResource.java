package io.hortora.garden.outcome;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/garden/outcomes")
@Produces(MediaType.APPLICATION_JSON)
public class OutcomeResource {

    @Inject GardenOutcomeService outcomeService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @io.smallrye.common.annotation.Blocking
    public Response recordOutcome(OutcomeRequest request) {
        String result = outcomeService.recordOutcome(
                request.geId(), request.issueRepo(), request.issueNumber(),
                request.workContext(), request.successRate(), request.detail());
        return Response.ok(new OutcomeResponse(result)).build();
    }

    @GET
    @Path("/report")
    @Produces(MediaType.TEXT_PLAIN)
    @io.smallrye.common.annotation.Blocking
    public String outcomeReport() {
        return outcomeService.outcomeReport();
    }

    public record OutcomeRequest(String geId, String issueRepo, int issueNumber,
                                  String workContext, double successRate, String detail) {}

    public record OutcomeResponse(String message) {}
}
