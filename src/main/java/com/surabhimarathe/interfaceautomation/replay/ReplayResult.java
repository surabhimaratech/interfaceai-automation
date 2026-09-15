package com.surabhimarathe.interfaceautomation.replay;

import java.util.Map;

/** Outputs are sensitive in-memory return values, never diagnostics. No exception details are retained. */
public record ReplayResult(Status status, Code code, String outcomeCode, int step, Map<String, Object> outputs,
                           Disposition disposition, ReplayDiagnostic diagnostic, DiagnosticPersistence diagnosticPersistence, int handoffCount) {
    public ReplayResult(Status status, Code code, String outcomeCode, int step, Map<String,Object> outputs,
                        Disposition disposition, ReplayDiagnostic diagnostic, DiagnosticPersistence persistence) {
        this(status,code,outcomeCode,step,outputs,disposition,diagnostic,persistence,0);
    }
    public enum Disposition { SUCCESS, EXPECTED_OUTCOME, RECOVERABLE, POLICY_BLOCK, HARD_FAILURE }
    public enum DiagnosticPersistence { DISABLED, NOT_APPLICABLE, STORED, COLLISION, FAILED }
    public ReplayResult(Status status, Code code, String outcomeCode, int step, Map<String, Object> outputs) {
        this(status, code, outcomeCode, step, outputs, classify(status, code), null, DiagnosticPersistence.DISABLED);
    }
    private static Disposition classify(Status status, Code code) {
        if (status == Status.SUCCEEDED) return Disposition.SUCCESS;
        if (status == Status.EXPECTED_OUTCOME) return Disposition.EXPECTED_OUTCOME;
        if (policyBlock(code)) return Disposition.POLICY_BLOCK;
        return code == Code.TIMEOUT || code == Code.HUMAN_ACTION_REQUIRED ? Disposition.RECOVERABLE : Disposition.HARD_FAILURE;
    }
    public enum Status { SUCCEEDED, EXPECTED_OUTCOME, BLOCKED, FAILED }
    public enum Code { CHECKPOINT_VERIFIED, BUSINESS_OUTCOME, POLICY_DENIED, INVALID_ARTIFACT,
        INVALID_PARAMETERS, UNKNOWN_TARGET, UNKNOWN_TENANT, UNKNOWN_TENANT_TARGET, ZERO_LOCATOR, AMBIGUOUS_LOCATOR, POSTCONDITION_FAILED,
        CHECKPOINT_FAILED, EXTRACTION_FAILED, TIMEOUT, UNEXPECTED_DIALOG, INTERRUPTED, BROWSER_FAILURE,
        HUMAN_ACTION_REQUIRED, HANDOFF_TIMEOUT, HANDOFF_LIMIT, OWNERSHIP_DENIED,
        APPROVAL_REQUIRED, APPROVAL_SUSPENDED, GOVERNANCE_FAILURE }
    public ReplayResult {
        // Diagnostic is optional; outcomeCode is conditional. All other reference fields are mandatory.
        if (handoffCount < 0 || handoffCount > 10 || status == null || code == null || outputs == null || disposition == null || diagnosticPersistence == null)
            throw new IllegalArgumentException("INVALID_REPLAY_RESULT");
        boolean allowed = switch (status) {
            case SUCCEEDED -> code == Code.CHECKPOINT_VERIFIED;
            case EXPECTED_OUTCOME -> code == Code.BUSINESS_OUTCOME;
            case BLOCKED -> policyBlock(code);
            case FAILED -> code != Code.CHECKPOINT_VERIFIED && code != Code.BUSINESS_OUTCOME && !policyBlock(code);
        };
        if (!allowed || disposition != classify(status, code))
            throw new IllegalArgumentException("INVALID_REPLAY_RESULT");
        if (status == Status.EXPECTED_OUTCOME) {
            // Match the artifact outcome identifier grammar and bound; never accept arbitrary printable text.
            if (outcomeCode == null || outcomeCode.length() > 80 || !outcomeCode.matches("[A-Za-z][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("INVALID_REPLAY_RESULT");
        } else if (outcomeCode != null) throw new IllegalArgumentException("INVALID_REPLAY_RESULT");
        outputs = Map.copyOf(outputs);
    }
    /** Business codes remain separate from built-in failure codes, with a unified calling-agent view. */
    public String effectiveCode() { return status == Status.EXPECTED_OUTCOME ? outcomeCode : code.name(); }
    @Override public String toString() {
        return "ReplayResult[status=" + status + ", disposition=" + disposition + ", code=" + effectiveCode() + ", step=" + step
                + (diagnosticPersistence == DiagnosticPersistence.DISABLED ? "" : ", diagnostics=" + diagnosticPersistence)
                + ", outputs=REDACTED]";
    }
    ReplayResult withDiagnostic(ReplayDiagnostic value) {
        return new ReplayResult(status, code, outcomeCode, step, outputs, disposition, value, diagnosticPersistence,handoffCount);
    }
    ReplayResult withPersistence(DiagnosticPersistence value) {
        return new ReplayResult(status, code, outcomeCode, step, outputs, disposition, diagnostic, value,handoffCount);
    }
    ReplayResult withHandoffCount(int count) {
        return new ReplayResult(status,code,outcomeCode,step,outputs,disposition,diagnostic,diagnosticPersistence,count);
    }
    private static boolean policyBlock(Code code) {
        return code == Code.POLICY_DENIED || code == Code.APPROVAL_REQUIRED || code == Code.APPROVAL_SUSPENDED;
    }
    static ReplayResult failure(Code code, int step) {
        var diagnostics = new ReplayDiagnostics(Map.of());
        diagnostics.step(step, null);
        diagnostics.phase(switch (code) {
            case INVALID_PARAMETERS -> ReplayDiagnostic.Phase.PARAMETERS;
            case APPROVAL_REQUIRED, APPROVAL_SUSPENDED, GOVERNANCE_FAILURE -> ReplayDiagnostic.Phase.GOVERNANCE;
            case UNKNOWN_TARGET, UNKNOWN_TENANT, UNKNOWN_TENANT_TARGET, POLICY_DENIED -> ReplayDiagnostic.Phase.TARGET_RESOLUTION;
            default -> ReplayDiagnostic.Phase.VALIDATION;
        });
        return new ReplayResult(policyBlock(code) ? Status.BLOCKED : Status.FAILED, code, null, step, Map.of())
                .withDiagnostic(diagnostics.snapshot(code));
    }
}
