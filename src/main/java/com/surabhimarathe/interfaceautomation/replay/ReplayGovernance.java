package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.approval.*;
import java.util.Objects;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalState.Lifecycle.*;

/** Trusted host attachment. Replay derives observations internally, never from caller-supplied labels. */
public final class ReplayGovernance {
    private final ApprovalService service;
    public ReplayGovernance(ApprovalService service) { this.service = Objects.requireNonNull(service); }

    ApprovalState lookup(ArtifactIdentity identity) {
        try { return service.state(identity); }
        catch (ApprovalException ex) {
            if (ex.code() == ApprovalException.Code.UNKNOWN_IDENTITY) return null;
            throw ex;
        }
    }
    static ReplayResult.Code denied(ApprovalState state) {
        if (state == null || state.lifecycle() == DRAFT) return ReplayResult.Code.APPROVAL_REQUIRED;
        if (state.lifecycle() == SUSPENDED || state.approvedUnder() == null
                || !state.approvedUnder().eligible(state.reliability()))
            return ReplayResult.Code.APPROVAL_SUSPENDED;
        return null;
    }
    void record(ArtifactIdentity identity, ReplayResult result) {
        var state = service.observe(identity,observation(result));
        if (state.lifecycle() == APPROVED && !state.approvedUnder().eligible(state.reliability())) {
            service.suspend(identity,state.reliability().safetyFailures() > 0
                    ? SuspensionReason.SAFETY_REVIEW : SuspensionReason.RELIABILITY_REGRESSION);
        }
    }
    static ReliabilityObservation observation(ReplayResult result) {
        if (result.handoffCount() > 0) return ReliabilityObservation.HUMAN_ASSISTED;
        return switch (result.disposition()) {
            case SUCCESS -> ReliabilityObservation.SUCCESS;
            case EXPECTED_OUTCOME -> ReliabilityObservation.EXPECTED_OUTCOME;
            case RECOVERABLE -> ReliabilityObservation.RECOVERABLE_FAILURE;
            case POLICY_BLOCK -> ReliabilityObservation.POLICY_BLOCK;
            case HARD_FAILURE -> ReliabilityObservation.HARD_FAILURE;
        };
    }
    @Override public String toString() { return "ReplayGovernance[REDACTED]"; }
}
