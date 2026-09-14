package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.artifact.*;

/** Sanitized metadata only. Expectations are artifact declarations, never observed/resolved text. */
public record ReplayDiagnostic(ReplayResult.Code code, int step, String stepId, Phase phase, ExpectedLocator expected,
                               MatchCounts matches, Conditions conditions) {
    public enum Phase { VALIDATION, PARAMETERS, TARGET_RESOLUTION, LAUNCH, LOAD, OUTCOME,
        LOCATOR, ACTION, POSTCONDITION, CHECKPOINT, EXTRACTION, CLEANUP, SESSION_CHECK, HANDOFF, RESUMING }
    public record ExpectedLocator(LocatorSpec.Role role, String name, LocatorSpec.NameMatch nameMatch,
                                  ContextSpec context, LocatorSpec.Cardinality cardinality) {}
    /** visible=-1 means not measured; counts are capped at 201, with capped=true. */
    public record MatchCounts(int visible, boolean zero, boolean ambiguous, boolean capped) {
        static MatchCounts unknown() { return new MatchCounts(-1, false, false, false); }
        static MatchCounts of(int count) { return new MatchCounts(Math.min(201, count), count == 0, count > 1, count > 200); }
    }
    public record Conditions(boolean actionStarted, boolean requestDenied, boolean policyDenied,
                             boolean unexpectedDialog, boolean unexpectedPage, boolean timedOut,
                             boolean postconditionFailed, boolean checkpointFailed, boolean outcomeDetected,
                             boolean sessionExpiryDetected, int handoffCount, boolean humanActionRequired,
                             boolean handoffTimedOut, boolean handoffLimitReached) {}
}
