package com.surabhimarathe.interfaceautomation.approval;

import java.time.Instant;

public record ApprovalState(ArtifactIdentity identity, Lifecycle lifecycle, Reliability reliability,
        Instant approvedAt, String approverHash, SuspensionReason suspensionReason, ApprovalCriteria approvedUnder) {
    public enum Lifecycle { DRAFT, APPROVED, SUSPENDED }
    public ApprovalState {
        if (identity == null || lifecycle == null || reliability == null
                || (lifecycle == Lifecycle.DRAFT ? approvedAt != null || approverHash != null || approvedUnder != null
                    : approvedAt == null || !Hashes.digest(approverHash) || approvedUnder == null)
                || (lifecycle == Lifecycle.SUSPENDED) != (suspensionReason != null))
            throw ApprovalException.fail(ApprovalException.Code.INVALID_EVENT);
    }
    @Override public String toString() { return "ApprovalState[lifecycle="+lifecycle+", reliability="+reliability+", identities=REDACTED]"; }
}
