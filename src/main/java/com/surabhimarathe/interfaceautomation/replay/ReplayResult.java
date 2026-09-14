package com.surabhimarathe.interfaceautomation.replay;

import java.util.Map;

/** Outputs are sensitive in-memory return values, never diagnostics. No exception details are retained. */
public record ReplayResult(Status status, Code code, String outcomeCode, int step, Map<String, Object> outputs) {
    public enum Status { SUCCEEDED, EXPECTED_OUTCOME, BLOCKED, FAILED }
    public enum Code { CHECKPOINT_VERIFIED, BUSINESS_OUTCOME, POLICY_DENIED, INVALID_ARTIFACT,
        INVALID_PARAMETERS, UNKNOWN_TARGET, ZERO_LOCATOR, AMBIGUOUS_LOCATOR, POSTCONDITION_FAILED,
        CHECKPOINT_FAILED, EXTRACTION_FAILED, TIMEOUT, BROWSER_FAILURE }
    public ReplayResult { outputs = Map.copyOf(outputs); }
    /** Business codes remain separate from built-in failure codes, with a unified calling-agent view. */
    public String effectiveCode() { return status == Status.EXPECTED_OUTCOME ? outcomeCode : code.name(); }
    @Override public String toString() {
        return "ReplayResult[status=" + status + ", code=" + effectiveCode() + ", step=" + step + ", outputs=REDACTED]";
    }
    static ReplayResult failure(Code code, int step) {
        return new ReplayResult(code == Code.POLICY_DENIED ? Status.BLOCKED : Status.FAILED, code, null, step, Map.of());
    }
}
