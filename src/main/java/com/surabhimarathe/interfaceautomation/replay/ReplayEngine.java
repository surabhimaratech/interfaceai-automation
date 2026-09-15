package com.surabhimarathe.interfaceautomation.replay;

import com.microsoft.playwright.*;
import com.surabhimarathe.interfaceautomation.artifact.*;
import com.surabhimarathe.interfaceautomation.approval.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.surabhimarathe.interfaceautomation.replay.ReplayResult.Code.*;

/** Deterministic entry point. No model dependency; sanitized diagnostic persistence is host opt-in only. */
public final class ReplayEngine {
    @FunctionalInterface interface BrowserLauncher {
        Browser launch(Playwright playwright, BrowserType.LaunchOptions options);
    }
    private final TenantTargetRegistry targets;
    private final TenantId compatibilityTenant;
    private final ReplayOptions options;
    private final BrowserLauncher launcher;
    private final DiagnosticStore diagnosticStore;
    private final ReplayHandoff handoff;
    private final ReplayGovernance governance;
    private static final class GovernanceRun { ArtifactIdentity identity; boolean record; }
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
        this(targets.registry(), options, launcher, store, handoff, targets.tenant(),null);
    }
    public ReplayEngine(TenantTargetRegistry targets, ReplayOptions options) {
        this(targets,options,null,null);
    }
    public ReplayEngine(TenantTargetRegistry targets, ReplayOptions options, DiagnosticStore store, ReplayHandoff handoff) {
        this(targets,options,(p,o) -> p.chromium().launch(o),store,handoff,null,null);
    }
    ReplayEngine(TenantTargetRegistry targets, ReplayOptions options, BrowserLauncher launcher, DiagnosticStore store, ReplayHandoff handoff) {
        this(targets,options,launcher,store,handoff,null,null);
    }
    public ReplayEngine(TargetRegistry targets, ReplayOptions options, DiagnosticStore store,
                        ReplayHandoff handoff, ReplayGovernance governance) {
        this(targets.registry(),options,(p,o) -> p.chromium().launch(o),store,handoff,targets.tenant(),governance);
    }
    public ReplayEngine(TenantTargetRegistry targets, ReplayOptions options, DiagnosticStore store,
                        ReplayHandoff handoff, ReplayGovernance governance) {
        this(targets,options,(p,o) -> p.chromium().launch(o),store,handoff,null,governance);
    }
    ReplayEngine(TargetRegistry targets, ReplayOptions options, BrowserLauncher launcher,
                 DiagnosticStore store, ReplayHandoff handoff, ReplayGovernance governance) {
        this(targets.registry(),options,launcher,store,handoff,targets.tenant(),governance);
    }
    ReplayEngine(TenantTargetRegistry targets, ReplayOptions options, BrowserLauncher launcher,
                 DiagnosticStore store, ReplayHandoff handoff, ReplayGovernance governance) {
        this(targets,options,launcher,store,handoff,null,governance);
    }
    private ReplayEngine(TenantTargetRegistry targets, ReplayOptions options, BrowserLauncher launcher,
                         DiagnosticStore store, ReplayHandoff handoff, TenantId compatibilityTenant, ReplayGovernance governance) {
        this.governance = governance;
        this.compatibilityTenant = compatibilityTenant;
        this.handoff = handoff;
        this.diagnosticStore = store;
        this.targets = targets;
        this.options = options;
        this.launcher = launcher;
    }

    /** Missing mode always fails closed; retained only for source compatibility. */
    public ReplayResult run(String json, InvocationParameters invocation) {
        return runScoped(compatibilityTenant,null,invocation,true,null);
    }
    public ReplayResult run(TenantId tenant, String json, InvocationParameters invocation) {
        return runScoped(tenant,null,invocation,false,null);
    }
    public ReplayResult runValidation(String json, InvocationParameters invocation) {
        return run(json,invocation,ReplayMode.VALIDATION);
    }
    public ReplayResult runValidation(TenantId tenant, String json, InvocationParameters invocation) {
        return run(tenant,json,invocation,ReplayMode.VALIDATION);
    }
    public ReplayResult run(String json, InvocationParameters invocation, ReplayMode mode) {
        return runScoped(compatibilityTenant,encode(json),invocation,true,mode);
    }
    public ReplayResult run(TenantId tenant, String json, InvocationParameters invocation, ReplayMode mode) {
        return runScoped(tenant,encode(json),invocation,false,mode);
    }
    public ReplayResult run(byte[] bytes, InvocationParameters invocation, ReplayMode mode) {
        return runScoped(compatibilityTenant,bytes,invocation,true,mode);
    }
    /** Primary boundary: snapshot unchanged UTF-8 bytes before any asynchronous work. */
    public ReplayResult run(TenantId tenant, byte[] bytes, InvocationParameters invocation, ReplayMode mode) {
        return runScoped(tenant,bytes,invocation,false,mode);
    }
    private static byte[] encode(String json) {
        if (json == null) return null;
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(json));
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
        } catch (CharacterCodingException ex) { return null; }
    }
    private ReplayResult runScoped(TenantId tenant, byte[] artifactBytes, InvocationParameters invocation,
                                   boolean compatibility, ReplayMode mode) {
        byte[] bytes = artifactBytes == null ? null : artifactBytes.clone();
        var run = new GovernanceRun();
        if (handoff != null && !handoff.claim()) return ReplayResult.failure(INVALID_PARAMETERS,0);
        ReplayResult result;
        try { result = execute(tenant, bytes, invocation, compatibility,mode,run); }
        finally { if (handoff != null) handoff.close(); }
        result = result.withHandoffCount(handoff == null ? 0 : handoff.transferCount());
        if (run.record) {
            try { governance.record(run.identity,result); }
            catch (RuntimeException ex) {
                result = ReplayResult.failure(GOVERNANCE_FAILURE,result.step()).withHandoffCount(result.handoffCount());
            }
        }
        if (result.status() != ReplayResult.Status.SUCCEEDED && result.diagnostic() == null) {
            var diagnostics = new ReplayDiagnostics(Map.of());
            diagnostics.phase(switch (result.code()) {
                case INVALID_PARAMETERS -> ReplayDiagnostic.Phase.PARAMETERS;
                case UNKNOWN_TARGET, UNKNOWN_TENANT, UNKNOWN_TENANT_TARGET, POLICY_DENIED -> ReplayDiagnostic.Phase.TARGET_RESOLUTION;
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

    private ReplayResult execute(TenantId tenant, byte[] bytes, InvocationParameters invocation, boolean compatibility,
                                 ReplayMode mode, GovernanceRun run) {
        long deadline = System.nanoTime() + options.overallTimeout().toNanos();
        if (mode == null) return ReplayResult.failure(INVALID_PARAMETERS,0);
        if (tenant == null) return ReplayResult.failure(INVALID_PARAMETERS,0);
        if (!targets.containsTenant(tenant)) return ReplayResult.failure(UNKNOWN_TENANT,0);
        if (handoff != null && options.headless()) return ReplayResult.failure(INVALID_PARAMETERS,0);
        CapabilityArtifact artifact;
        try {
            if (bytes == null || bytes.length > 512_000) return ReplayResult.failure(INVALID_ARTIFACT,0);
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            artifact = new ArtifactJson().read(json);
        }
        catch (RuntimeException | CharacterCodingException ex) { return ReplayResult.failure(INVALID_ARTIFACT, 0); }
        // Dynamic outcome identifiers are printable: do not allow them to echo configured tenant IDs.
        if (artifact.outcomes().stream().anyMatch(o -> targets.diagnosticSecrets().stream()
                .anyMatch(id -> o.code().toLowerCase(Locale.ROOT).contains(id.toLowerCase(Locale.ROOT)))))
            return ReplayResult.failure(INVALID_ARTIFACT,0);
        if (mode == ReplayMode.VALIDATION && governance != null) {
            try {
                run.identity = ArtifactIdentity.from(tenant,bytes);
                var state = governance.lookup(run.identity);
                if (state != null && state.lifecycle() == ApprovalState.Lifecycle.SUSPENDED)
                    return ReplayResult.failure(APPROVAL_SUSPENDED,0);
                run.record = state != null;
            } catch (RuntimeException ex) { return ReplayResult.failure(GOVERNANCE_FAILURE,0); }
        }
        var configuration = targets.resolve(tenant, artifact.target().targetId());
        if (configuration == null) return ReplayResult.failure(compatibility && compatibilityTenant != null
                ? UNKNOWN_TARGET : UNKNOWN_TENANT_TARGET,0);
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
        var policy = configuration.policy();
        String entry = policy.origin() + artifact.target().entryPath();
        boolean entryAllowed = policy.allowsUrl(entry);
        if (mode == ReplayMode.UNATTENDED) {
            if (governance == null) return ReplayResult.failure(GOVERNANCE_FAILURE,0);
            try {
                run.identity = ArtifactIdentity.from(tenant,bytes);
                var denial = ReplayGovernance.denied(governance.lookup(run.identity));
                if (denial != null) return ReplayResult.failure(denial,0);
                run.record = true;
            } catch (RuntimeException ex) { return ReplayResult.failure(GOVERNANCE_FAILURE,0); }
        }
        // Record entry-policy failures only after exact unattended approval is established.
        if (!entryAllowed) return ReplayResult.failure(POLICY_DENIED, 0);
        if (System.nanoTime() >= deadline) return ReplayResult.failure(TIMEOUT, 0);
        AtomicInteger step = new AtomicInteger();
        ReplayDiagnostics diagnostics = new ReplayDiagnostics(inputs, configuration.redactionPolicy(), targets.diagnosticSecrets());
        diagnostics.phase(ReplayDiagnostic.Phase.LAUNCH);
        // All Playwright operations and cleanup stay on this single owner thread.
        ExecutorService owner = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "artifact-replay-owner");
            t.setDaemon(true);
            return t;
        });
        Future<ReplayResult> pending = owner.submit(() ->
                new ReplayExecution(artifact, inputs, policy, options, launcher, deadline, step, diagnostics,
                        configuration.expiryMarker(), handoff).run(entry));
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
