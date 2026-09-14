package com.surabhimarathe.interfaceautomation.replay;

import com.microsoft.playwright.*;
import com.surabhimarathe.interfaceautomation.artifact.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.surabhimarathe.interfaceautomation.replay.ReplayResult.Code.*;

/** Deterministic entry point. No model dependency; sanitized diagnostic persistence is host opt-in only. */
public final class ReplayEngine {
    @FunctionalInterface interface BrowserLauncher {
        Browser launch(Playwright playwright, BrowserType.LaunchOptions options);
    }
    private final TargetRegistry targets;
    private final ReplayOptions options;
    private final BrowserLauncher launcher;
    private final DiagnosticStore diagnosticStore;
    private final ReplayHandoff handoff;
    public ReplayEngine(TargetRegistry targets, ReplayOptions options) {
        this(targets, options, (p, o) -> p.chromium().launch(o), null);
    }
    public ReplayEngine(TargetRegistry targets, ReplayOptions options, DiagnosticStore store) {
        this(targets, options, (p, o) -> p.chromium().launch(o), store);
    }
    ReplayEngine(TargetRegistry targets, ReplayOptions options, BrowserLauncher launcher) {
        this(targets, options, launcher, null);
    }
    public ReplayEngine(TargetRegistry targets, ReplayOptions options, DiagnosticStore store, ReplayHandoff handoff) {
        this(targets, options, (p,o) -> p.chromium().launch(o), store, handoff);
    }
    ReplayEngine(TargetRegistry targets, ReplayOptions options, BrowserLauncher launcher, DiagnosticStore store) {
        this(targets, options, launcher, store, null);
    }
    ReplayEngine(TargetRegistry targets, ReplayOptions options, BrowserLauncher launcher, DiagnosticStore store, ReplayHandoff handoff) {
        this.handoff = handoff;
        this.diagnosticStore = store;
        this.targets = targets;
        this.options = options;
        this.launcher = launcher;
    }

    public ReplayResult run(String artifactJson, InvocationParameters invocation) {
        if (handoff != null && !handoff.claim()) return ReplayResult.failure(INVALID_PARAMETERS,0);
        ReplayResult result;
        try { result = execute(artifactJson, invocation); }
        finally { if (handoff != null) handoff.close(); }
        if (result.status() != ReplayResult.Status.SUCCEEDED && result.diagnostic() == null) {
            var diagnostics = new ReplayDiagnostics(Map.of());
            diagnostics.phase(switch (result.code()) {
                case INVALID_PARAMETERS -> ReplayDiagnostic.Phase.PARAMETERS;
                case UNKNOWN_TARGET, POLICY_DENIED -> ReplayDiagnostic.Phase.TARGET_RESOLUTION;
                default -> ReplayDiagnostic.Phase.VALIDATION;
            });
            result = result.withDiagnostic(diagnostics.snapshot(result.code()));
        }
        if (diagnosticStore == null) return result;
        if (result.diagnostic() == null) return result.withPersistence(ReplayResult.DiagnosticPersistence.NOT_APPLICABLE);
        try {
            diagnosticStore.persist(result.diagnostic());
            return result.withPersistence(ReplayResult.DiagnosticPersistence.STORED);
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            return result.withPersistence(ReplayResult.DiagnosticPersistence.COLLISION);
        } catch (java.io.IOException | RuntimeException ex) {
            return result.withPersistence(ReplayResult.DiagnosticPersistence.FAILED);
        }
    }

    private ReplayResult execute(String artifactJson, InvocationParameters invocation) {
        long deadline = System.nanoTime() + options.overallTimeout().toNanos();
        if (handoff != null && options.headless()) return ReplayResult.failure(INVALID_PARAMETERS,0);
        CapabilityArtifact artifact;
        try { artifact = new ArtifactJson().read(artifactJson); }
        catch (RuntimeException ex) { return ReplayResult.failure(INVALID_ARTIFACT, 0); }
        Map<String, Object> inputs;
        try {
            if (invocation == null || invocation.values() == null
                    || !invocation.values().keySet().equals(artifact.inputs().keySet()))
                return ReplayResult.failure(INVALID_PARAMETERS, 0);
            inputs = Map.copyOf(invocation.values());
            for (var e : artifact.inputs().entrySet())
                if (!ContractValues.valid(e.getValue().type(), e.getValue().constraints(), inputs.get(e.getKey())))
                    return ReplayResult.failure(INVALID_PARAMETERS, 0);
        } catch (RuntimeException ex) { return ReplayResult.failure(INVALID_PARAMETERS, 0); }
        var policy = targets.resolve(artifact.target().targetId());
        if (policy == null) return ReplayResult.failure(UNKNOWN_TARGET, 0);
        String entry = policy.origin() + artifact.target().entryPath();
        if (!policy.allowsUrl(entry)) return ReplayResult.failure(POLICY_DENIED, 0);
        if (System.nanoTime() >= deadline) return ReplayResult.failure(TIMEOUT, 0);
        AtomicInteger step = new AtomicInteger();
        ReplayDiagnostics diagnostics = new ReplayDiagnostics(inputs);
        diagnostics.phase(ReplayDiagnostic.Phase.LAUNCH);
        // All Playwright operations and cleanup stay on this single owner thread.
        ExecutorService owner = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "artifact-replay-owner");
            t.setDaemon(true);
            return t;
        });
        Future<ReplayResult> pending = owner.submit(() ->
                new ReplayExecution(artifact, inputs, policy, options, launcher, deadline, step, diagnostics,
                        targets.expiryMarker(artifact.target().targetId()), handoff).run(entry));
        try {
            return pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            return diagnostics.failure(TIMEOUT);
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return diagnostics.failure(INTERRUPTED);
        } catch (ExecutionException ex) {
            return diagnostics.failure(BROWSER_FAILURE);
        } finally {
            owner.shutdown();
        }
    }
}
