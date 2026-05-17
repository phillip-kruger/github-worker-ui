package io.quarkus.houseelves.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.websockets.next.OpenConnections;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class StateWatcher {

    private volatile FileTime lastModified;

    @Inject
    OpenConnections connections;

    @Scheduled(every = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pollState() {
        if (connections.stream().findAny().isEmpty()) return;

        try {
            if (!Files.exists(LiveSocket.STATE_PATH)) return;
            FileTime current = Files.getLastModifiedTime(LiveSocket.STATE_PATH);
            if (lastModified == null || current.compareTo(lastModified) > 0) {
                lastModified = current;
                String state = LiveSocket.readState();
                connections.forEach(c -> c.sendText(state));
            }
        } catch (IOException ignored) {
        }
    }
}
