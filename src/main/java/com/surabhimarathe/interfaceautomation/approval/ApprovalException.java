package com.surabhimarathe.interfaceautomation.approval;

/** Fixed failures only: no causes, identifiers, paths or input excerpts. */
public final class ApprovalException extends RuntimeException {
    public enum Code {
        INVALID_IDENTITY, INVALID_ARTIFACT, INVALID_POLICY, INVALID_ACTOR,
        UNKNOWN_IDENTITY, INVALID_TRANSITION, NOT_ELIGIBLE, INVALID_EVENT,
        CORRUPT_HISTORY, STORAGE_FAILURE, OUTPUT_COLLISION, LIMIT_REACHED
    }
    private final Code code;
    public ApprovalException(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
    static ApprovalException fail(Code code) { return new ApprovalException(code); }
}
