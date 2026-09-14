package com.surabhimarathe.interfaceautomation.replay;

import com.microsoft.playwright.*;
import com.surabhimarathe.interfaceautomation.artifact.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.surabhimarathe.interfaceautomation.replay.ReplayResult.Code.*;

/** Deterministic entry point. No discovery runner, model dependency, logging, or evidence writes. */
public final class ReplayEngine {
    @FunctionalInterface interface BrowserLauncher {
        Browser launch(Playwright playwright, BrowserType.LaunchOptions options);
    }
    private final TargetRegistry targets;
    private final ReplayOptions options;
    private final BrowserLauncher launcher;
    public ReplayEngine(TargetRegistry targets, ReplayOptions options) {
        this(targets, options, (p, o) -> p.chromium().launch(o));
    }
    ReplayEngine(TargetRegistry targets, ReplayOptions options, BrowserLauncher launcher) {
        this.targets = targets;
        this.options = options;
        this.launcher = launcher;
    }

    public ReplayResult run(String artifactJson, InvocationParameters invocation) {
        long deadline = System.nanoTime() + options.overallTimeout().toNanos();
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
        // All Playwright operations and cleanup stay on this single owner thread.
        ExecutorService owner = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "artifact-replay-owner");
            t.setDaemon(true);
            return t;
        });
        Future<ReplayResult> pending = owner.submit(() ->
                new ReplayExecution(artifact, inputs, policy, options, launcher, deadline, step).run(entry));
        try {
            return pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            return ReplayResult.failure(TIMEOUT, step.get());
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return ReplayResult.failure(TIMEOUT, step.get());
        } catch (ExecutionException ex) {
            return ReplayResult.failure(BROWSER_FAILURE, step.get());
        } finally {
            owner.shutdown();
        }
    }
}
