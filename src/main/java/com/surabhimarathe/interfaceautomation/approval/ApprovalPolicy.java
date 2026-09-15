package com.surabhimarathe.interfaceautomation.approval;

import static com.surabhimarathe.interfaceautomation.approval.ApprovalException.*;

/** Trusted immutable configuration. Zero safety failures is mandatory, not a disableable option. */
public record ApprovalPolicy(int minimumEligibleRuns, int minimumScoreBasisPoints) {
    public ApprovalPolicy {
        if (minimumEligibleRuns < 1 || minimumEligibleRuns > 100 || minimumScoreBasisPoints < 0
                || minimumScoreBasisPoints > 10_000) throw fail(Code.INVALID_POLICY);
    }
    public static ApprovalPolicy defaults() { return new ApprovalPolicy(5,5_000); }
    public boolean eligible(Reliability r) {
        return r != null && r.eligible() >= minimumEligibleRuns && r.scoreBasisPoints() >= minimumScoreBasisPoints
                && r.safetyFailures() == 0;
    }
}
