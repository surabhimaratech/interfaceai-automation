package com.surabhimarathe.interfaceautomation.discovery;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Schema-based redaction: free text, URLs, arguments, observations and exceptions are omitted. */
public final class SafeEvents {
    private final Path file;
    private final UUID runId;
    private final ObjectMapper json = new ObjectMapper();
    public SafeEvents(Path file) throws IOException { this(file, UUID.randomUUID()); }
    public SafeEvents(Path file, UUID runId) throws IOException {
        this.file = file;
        this.runId = Objects.requireNonNull(runId);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.createFile(file); // Never overwrite previous evidence.
    }
    public UUID runId() { return runId; }
    public boolean hasCorrelatedFilename() { return file.getFileName().toString().contains(runId.toString()); }
    public synchronized void record(RunState state, UiAction.Type action, ActionResult.Code code,
                                    String model, boolean liveModel) throws IOException {
        write(state, action, code, model, liveModel, null);
    }
    public synchronized void recordStep(RunState state, UiAction.Type action, ActionResult.Code code,
                                        boolean liveModel, int step) throws IOException {
        write(state, action, code, DecisionModel.PINNED_ID, liveModel, step);
    }
    public synchronized void control(HandoffCoordinator.Owner from, HandoffCoordinator.Owner to,
                                     HandoffCoordinator.Reason reason, int step, long epoch, int interactions) throws IOException {
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString()); event.put("runId", runId);
        event.put("event", "CONTROL_TRANSFER"); event.put("from", from.name()); event.put("to", to.name());
        if (reason != null) event.put("reason", reason.name());
        event.put("step", step); event.put("epoch", epoch); event.put("manualInputEvents", interactions);
        Files.writeString(file, json.writeValueAsString(event) + System.lineSeparator(), StandardOpenOption.APPEND);
    }
    private void write(RunState state, UiAction.Type action, ActionResult.Code code,
                       String model, boolean liveModel, Integer step) throws IOException {
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("runId", runId);
        event.put("state", state.name());
        event.put("liveModel", liveModel);
        if (step != null) event.put("step", step);
        if (action != null) event.put("action", action.name());
        if (code != null) event.put("code", code.name());
        if (model != null) event.put("model", DecisionModel.PINNED_ID);
        Files.writeString(file, json.writeValueAsString(event) + System.lineSeparator(), StandardOpenOption.APPEND);
    }
}
