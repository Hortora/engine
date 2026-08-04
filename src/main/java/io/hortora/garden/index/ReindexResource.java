package io.hortora.garden.index;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/garden/reindex")
@Produces(MediaType.APPLICATION_JSON)
public class ReindexResource {

    @Inject GardenReindexService reindexService;

    @POST
    @io.smallrye.common.annotation.Blocking
    public Response reindex() {
        GardenReindexService.ReindexResult result = reindexService.reindex();
        if ("error".equals(result.status())) {
            return Response.serverError().entity(result).build();
        }
        return Response.ok(result).build();
    }
}
