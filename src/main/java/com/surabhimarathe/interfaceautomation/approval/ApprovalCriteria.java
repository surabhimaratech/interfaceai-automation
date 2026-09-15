package com.surabhimarathe.interfaceautomation.approval;

import static com.surabhimarathe.interfaceautomation.approval.ApprovalException.*;

/** Immutable, explicit decision-time criteria. The safety rule cannot be disabled. */
public record ApprovalCriteria(int minimumEligibleRuns, int minimumScoreBasisPoints,
                               boolean requireZeroSafetyFailures) {
    public ApprovalCriteria {
        if (minimumEligibleRuns < 1 || minimumEligibleRuns > 100 || minimumScoreBasisPoints < 0
                || minimumScoreBasisPoints > 10_000 || !requireZeroSafetyFailures)
            throw fail(Code.INVALID_POLICY);
    }
    public static ApprovalCriteria from(ApprovalPolicy policy) {
        if (policy == null) throw fail(Code.INVALID_POLICY);
        return new ApprovalCriteria(policy.minimumEligibleRuns(),policy.minimumScoreBasisPoints(),true);
    }
    public boolean eligible(Reliability reliability) {
        return reliability != null && reliability.eligible() >= minimumEligibleRuns
                && reliability.scoreBasisPoints() >= minimumScoreBasisPoints && reliability.safetyFailures() == 0;
    }
}
