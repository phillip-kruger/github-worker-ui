package io.quarkus.houseelves.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@jakarta.ws.rs.Path("/api/state")
public class StateResource {

    private static final Path STATE_PATH = Path.of(System.getProperty("user.home"),
            ".config", "github-worker", "state.json");

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getState() {
        if (!Files.exists(STATE_PATH)) {
            return Response.ok("{\"issues\":{},\"reviews\":{}}").build();
        }
        try {
            String json = Files.readString(STATE_PATH);
            return Response.ok(json).build();
        } catch (IOException e) {
            return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }
}
