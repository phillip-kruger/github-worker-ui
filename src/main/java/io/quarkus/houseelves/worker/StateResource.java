package io.quarkus.houseelves.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@jakarta.ws.rs.Path("/api/state")
public class StateResource {

    private static final Path STATE_PATH = Path.of(System.getProperty("user.home"),
            ".config", "github-worker", "state.json");

    private final ObjectMapper mapper = new ObjectMapper();

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

    @POST
    @jakarta.ws.rs.Path("/{key}/retry")
    @Produces(MediaType.APPLICATION_JSON)
    public Response retry(@PathParam("key") String key) {
        // key comes in as owner_repo#number (slashes encoded), decode it
        key = key.replace("_", "/").replaceFirst("/", "_").replace("_", "/");

        try {
            ObjectNode root = (ObjectNode) mapper.readTree(Files.readString(STATE_PATH));
            ObjectNode issues = (ObjectNode) root.get("issues");
            ObjectNode reviews = (ObjectNode) root.get("reviews");

            if (issues != null && issues.has(key)) {
                ObjectNode entry = (ObjectNode) issues.get(key);
                String currentState = entry.get("state").asText();

                // Reset to a retryable state
                String newState = switch (currentState) {
                    case "AWAITING_APPROVAL" -> "NEW";
                    case "CODING" -> "CODING";
                    case "SELF_REVIEWING" -> "CODING";
                    case "FIXING_REVIEW" -> "SELF_REVIEWING";
                    case "READY_FOR_REVIEW" -> "SELF_REVIEWING";
                    case "ADDRESSING_FEEDBACK" -> "READY_FOR_REVIEW";
                    case "SQUASHING" -> "READY_FOR_REVIEW";
                    case "MONITORING_CI" -> "SQUASHING";
                    case "FIXING_CI" -> "SQUASHING";
                    case "DONE" -> "NEW";
                    case "MERGED" -> "NEW";
                    default -> currentState;
                };

                entry.put("state", newState);
                entry.put("lastUpdated", Instant.now().toString());

                saveState(root);
                return Response.ok(Map.of("status", "reset", "key", key,
                        "from", currentState, "to", newState)).build();
            }

            if (reviews != null && reviews.has(key)) {
                ObjectNode entry = (ObjectNode) reviews.get(key);
                String currentState = entry.get("state").asText();
                entry.put("state", "NEW");
                entry.put("lastUpdated", Instant.now().toString());

                saveState(root);
                return Response.ok(Map.of("status", "reset", "key", key,
                        "from", currentState, "to", "NEW")).build();
            }

            return Response.status(404).entity(Map.of("error", "Key not found: " + key)).build();

        } catch (IOException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @DELETE
    @jakarta.ws.rs.Path("/{key}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response remove(@PathParam("key") String key) {
        key = key.replace("_", "/").replaceFirst("/", "_").replace("_", "/");

        try {
            ObjectNode root = (ObjectNode) mapper.readTree(Files.readString(STATE_PATH));
            ObjectNode issues = (ObjectNode) root.get("issues");
            ObjectNode reviews = (ObjectNode) root.get("reviews");

            boolean removed = false;
            if (issues != null && issues.has(key)) {
                issues.remove(key);
                removed = true;
            }
            if (reviews != null && reviews.has(key)) {
                reviews.remove(key);
                removed = true;
            }

            if (!removed) {
                return Response.status(404).entity(Map.of("error", "Key not found: " + key)).build();
            }

            saveState(root);
            return Response.ok(Map.of("status", "removed", "key", key)).build();

        } catch (IOException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    private static final Path CONFIG_PATH = Path.of(System.getProperty("user.home"),
            ".config", "github-worker", "config");

    // Matches: owner/repo#123, https://github.com/owner/repo/issues/123, https://github.com/owner/repo/pull/123
    private static final Pattern KEY_PATTERN = Pattern.compile(
            "(?:https?://github\\.com/)?([^/]+/[^/#]+)(?:/(?:issues|pull)/)?(\\d+)");
    private static final Pattern HASH_PATTERN = Pattern.compile("([^/]+/[^#]+)#(\\d+)");

    @POST
    @jakarta.ws.rs.Path("/add")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response addItem(Map<String, String> body) {
        String input = body.getOrDefault("item", "").trim();
        if (input.isEmpty()) {
            return Response.status(400).entity(Map.of("error", "No item provided")).build();
        }

        String ownerRepo = null;
        int number = 0;

        // Try URL format first
        Matcher m = KEY_PATTERN.matcher(input);
        if (m.find()) {
            ownerRepo = m.group(1);
            number = Integer.parseInt(m.group(2));
        } else {
            // Try owner/repo#123 format
            m = HASH_PATTERN.matcher(input);
            if (m.find()) {
                ownerRepo = m.group(1);
                number = Integer.parseInt(m.group(2));
            }
        }

        if (ownerRepo == null || number == 0) {
            return Response.status(400).entity(Map.of("error",
                    "Could not parse. Use owner/repo#123 or a GitHub URL")).build();
        }

        String token = readConfigValue("GITHUB_TOKEN");
        if (token == null) {
            return Response.serverError().entity(Map.of("error", "GITHUB_TOKEN not configured")).build();
        }

        // Determine if it's an issue or a PR
        String type = detectType(ownerRepo, number, token);
        if (type == null) {
            return Response.status(404).entity(Map.of("error",
                    "Could not find " + ownerRepo + "#" + number)).build();
        }

        String title = fetchTitle(ownerRepo, number, type, token);

        try {
            ObjectNode root = (ObjectNode) mapper.readTree(Files.readString(STATE_PATH));

            String key = ownerRepo + "#" + number;

            if ("pr".equals(type)) {
                ObjectNode reviews = root.has("reviews") ? (ObjectNode) root.get("reviews") : mapper.createObjectNode();
                if (reviews.has(key)) {
                    return Response.ok(Map.of("status", "exists", "key", key, "type", "review")).build();
                }
                ObjectNode entry = mapper.createObjectNode();
                entry.put("state", "NEW");
                entry.put("lastUpdated", Instant.now().toString());
                entry.put("title", title);
                entry.put("ownerRepo", ownerRepo);
                entry.put("prNumber", number);
                reviews.set(key, entry);
                root.set("reviews", reviews);
                saveState(root);
                return Response.ok(Map.of("status", "added", "key", key, "type", "review")).build();
            } else {
                ObjectNode issues = root.has("issues") ? (ObjectNode) root.get("issues") : mapper.createObjectNode();
                if (issues.has(key)) {
                    return Response.ok(Map.of("status", "exists", "key", key, "type", "issue")).build();
                }
                ObjectNode entry = mapper.createObjectNode();
                entry.put("state", "NEW");
                entry.put("lastUpdated", Instant.now().toString());
                entry.put("title", title);
                entry.put("ownerRepo", ownerRepo);
                entry.put("issueNumber", number);
                entry.put("attempts", 0);
                issues.set(key, entry);
                root.set("issues", issues);
                saveState(root);
                return Response.ok(Map.of("status", "added", "key", key, "type", "issue")).build();
            }
        } catch (IOException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    private String detectType(String ownerRepo, int number, String token) {
        try {
            ProcessBuilder pb = new ProcessBuilder("gh", "api",
                    "repos/" + ownerRepo + "/pulls/" + number, "--jq", ".number");
            pb.environment().put("GH_TOKEN", token);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) return "pr";
        } catch (Exception ignored) {}

        try {
            ProcessBuilder pb = new ProcessBuilder("gh", "api",
                    "repos/" + ownerRepo + "/issues/" + number, "--jq", ".number");
            pb.environment().put("GH_TOKEN", token);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) return "issue";
        } catch (Exception ignored) {}

        return null;
    }

    private String fetchTitle(String ownerRepo, int number, String type, String token) {
        try {
            String endpoint = "pr".equals(type)
                    ? "repos/" + ownerRepo + "/pulls/" + number
                    : "repos/" + ownerRepo + "/issues/" + number;
            ProcessBuilder pb = new ProcessBuilder("gh", "api", endpoint, "--jq", ".title");
            pb.environment().put("GH_TOKEN", token);
            Process p = pb.start();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {}
        return ownerRepo + "#" + number;
    }

    private String readConfigValue(String key) {
        try {
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                line = line.strip();
                if (line.startsWith(key + "=")) {
                    return line.substring(key.length() + 1).strip();
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    private void saveState(JsonNode root) throws IOException {
        Path tmp = STATE_PATH.resolveSibling("state.json.tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
        Files.move(tmp, STATE_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
