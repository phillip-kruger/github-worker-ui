package io.quarkus.houseelves.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@jakarta.ws.rs.Path("/api/config")
public class ConfigResource {

    private static final Path CONFIG_PATH = Path.of(System.getProperty("user.home"),
            ".config", "github-worker", "config");

    private static final Set<String> SECRET_KEYS = Set.of(
            "GITHUB_TOKEN", "BOT_TOKEN", "GMAIL_APP_PASSWORD");

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfig() {
        if (!Files.exists(CONFIG_PATH)) {
            return Response.status(404).entity("{\"error\":\"Config file not found\"}").build();
        }
        try {
            Map<String, String> config = readConfig();
            for (String key : SECRET_KEYS) {
                if (config.containsKey(key)) {
                    String val = config.get(key);
                    config.put(key, val.length() > 8
                            ? val.substring(0, 4) + "****" + val.substring(val.length() - 4)
                            : "****");
                }
            }
            return Response.ok(config).build();
        } catch (IOException e) {
            return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateConfig(Map<String, String> updates) {
        try {
            Map<String, String> config = readConfig();

            for (var entry : updates.entrySet()) {
                if (SECRET_KEYS.contains(entry.getKey()) && entry.getValue().contains("****")) {
                    continue;
                }
                config.put(entry.getKey(), entry.getValue());
            }

            writeConfig(config);
            return Response.ok(Map.of("status", "saved")).build();
        } catch (IOException e) {
            return Response.serverError().entity("{\"error\":\"" + e.getMessage() + "\"}").build();
        }
    }

    private Map<String, String> readConfig() throws IOException {
        Map<String, String> config = new LinkedHashMap<>();
        for (String line : Files.readAllLines(CONFIG_PATH)) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) {
                config.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        return config;
    }

    private void writeConfig(Map<String, String> config) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (var entry : config.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
        Path tmp = CONFIG_PATH.resolveSibling("config.tmp");
        Files.writeString(tmp, sb.toString());
        Files.move(tmp, CONFIG_PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
