package io.quarkus.houseelves.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;

@WebSocket(path = "/api/live")
public class LiveSocket {

    static final Path STATE_PATH = Path.of(System.getProperty("user.home"),
            ".config", "github-worker", "state.json");

    @OnOpen
    public String onOpen() {
        return readState();
    }

    static String readState() {
        try {
            if (!Files.exists(STATE_PATH)) return "{\"issues\":{},\"reviews\":{}}";
            return Files.readString(STATE_PATH);
        } catch (IOException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
