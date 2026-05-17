package io.quarkus.houseelves.worker;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@jakarta.ws.rs.Path("/api/logs")
public class LogResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getLogs(@QueryParam("lines") @DefaultValue("100") int lines) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "journalctl", "--user-unit", "github-worker", "--no-pager",
                    "-n", String.valueOf(lines), "--output", "short-iso");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            String output;
            if (finished) {
                output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } else {
                process.destroyForcibly();
                output = "Log fetch timed out";
            }
            return Response.ok(Map.of("logs", output)).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }
}
