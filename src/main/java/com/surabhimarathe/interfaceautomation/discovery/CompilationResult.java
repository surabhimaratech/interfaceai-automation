package com.surabhimarathe.interfaceautomation.discovery;

import com.surabhimarathe.interfaceautomation.artifact.CapabilityArtifact;
import java.util.UUID;

/** Separate from discovery status; compilation failure never changes a successful UI result. */
public record CompilationResult(Code code, UUID runId, int successfulActions, CapabilityArtifact artifact) {
    public enum Code { INCOMPLETE, COMPILED, PERSISTED, DISCOVERY_NOT_VERIFIED, TRACE_NOT_REPLAYABLE,
        SEMANTIC_CAPTURE_FAILED, COMPILATION_REJECTED, OUTPUT_COLLISION, PERSISTENCE_FAILED }
    @Override public String toString() {
        return "CompilationResult[code=" + code + ", runId=" + runId + ", successfulActions=" + successfulActions + "]";
    }
}
