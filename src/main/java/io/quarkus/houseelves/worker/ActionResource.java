package io.quarkus.houseelves.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@jakarta.ws.rs.Path("/api")
public class ActionResource {

    @POST
    @jakarta.ws.rs.Path("/trigger")
    @Produces(MediaType.APPLICATION_JSON)
    public Response trigger() {
        try {
            ProcessBuilder pb = new ProcessBuilder("github-worker");
            pb.environment().put("PATH", System.getenv("PATH"));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            return Response.ok(Map.of("status", "triggered", "pid", process.pid())).build();
        } catch (IOException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @jakarta.ws.rs.Path("/preview")
    @Produces(MediaType.APPLICATION_JSON)
    public Response preview() {
        try {
            ProcessBuilder pb = new ProcessBuilder("github-worker", "--preview");
            pb.environment().put("PATH", System.getenv("PATH"));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            String output;
            if (finished) {
                output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } else {
                process.destroyForcibly();
                output = "Preview timed out after 60 seconds";
            }
            return Response.ok(Map.of("output", output)).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }
}
