package com.surabhimarathe.interfaceautomation.discovery;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Schema-based redaction: free text, URLs, arguments, observations and exceptions are omitted. */
public final class SafeEvents {
    private final Path file;
    private final String runId = UUID.randomUUID().toString();
    private final ObjectMapper json = new ObjectMapper();
    public SafeEvents(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.createFile(file); // Never overwrite previous evidence.
    }
    public synchronized void record(RunState state, UiAction.Type action, ActionResult.Code code,
                                    String model, boolean liveModel) throws IOException {
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("runId", runId);
        event.put("state", state.name());
        event.put("liveModel", liveModel);
        if (action != null) event.put("action", action.name());
        if (code != null) event.put("code", code.name());
        if (model != null) event.put("model", OpenRouterClient.MODEL);
        Files.writeString(file, json.writeValueAsString(event) + System.lineSeparator(), StandardOpenOption.APPEND);
    }
}
