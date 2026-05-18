package io.quarkus.houseelves.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@jakarta.ws.rs.Path("/api")
public class ActionResource {

    private static final Path CONFIG_PATH = Path.of(System.getProperty("user.home"),
            ".config", "github-worker", "config");

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
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            String output;
            if (finished) {
                output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } else {
                process.destroyForcibly();
                output = "Preview timed out after 120 seconds";
            }
            return Response.ok(Map.of("output", output)).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @jakarta.ws.rs.Path("/react/{owner}/{repo}/{number}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response addEyesReaction(@PathParam("owner") String owner,
                                     @PathParam("repo") String repo,
                                     @PathParam("number") int number) {
        String ownerRepo = owner + "/" + repo;
        String token = readConfigValue("GITHUB_TOKEN");
        if (token == null) {
            return Response.serverError().entity(Map.of("error", "GITHUB_TOKEN not configured")).build();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("gh", "api",
                    "repos/" + ownerRepo + "/issues/" + number + "/reactions",
                    "-X", "POST", "-f", "content=eyes");
            pb.environment().put("GH_TOKEN", token);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(15, TimeUnit.SECONDS);
            return Response.ok(Map.of("status", "reacted", "item", ownerRepo + "#" + number)).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @jakarta.ws.rs.Path("/assign/{owner}/{repo}/{number}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response selfAssign(@PathParam("owner") String owner,
                                @PathParam("repo") String repo,
                                @PathParam("number") int number) {
        String ownerRepo = owner + "/" + repo;
        String token = readConfigValue("GITHUB_TOKEN");
        String user = readConfigValue("GITHUB_USER");
        if (token == null || user == null) {
            return Response.serverError().entity(Map.of("error", "Config not found")).build();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("gh", "issue", "edit",
                    String.valueOf(number), "--repo", ownerRepo, "--add-assignee", user);
            pb.environment().put("GH_TOKEN", token);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor(15, TimeUnit.SECONDS);
            return Response.ok(Map.of("status", "assigned", "item", ownerRepo + "#" + number)).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    private String readConfigValue(String key) {
        try {
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                line = line.strip();
                if (line.startsWith(key + "=")) {
                    return line.substring(key.length() + 1).strip();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
