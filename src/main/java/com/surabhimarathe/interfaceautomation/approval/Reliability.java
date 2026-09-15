package com.surabhimarathe.interfaceautomation.approval;

import static com.surabhimarathe.interfaceautomation.approval.ApprovalException.*;

public record Reliability(long handled, long eligible, long assisted, long safetyFailures, int scoreBasisPoints) {
    public Reliability {
        if (handled < 0 || eligible < handled || eligible > 1_000_000_000L || assisted < 0
                || assisted > 1_000_000_000L || safetyFailures < 0 || safetyFailures > eligible - handled
                || scoreBasisPoints != wilson(handled,eligible)) throw fail(Code.INVALID_EVENT);
    }
    public static Reliability empty() { return new Reliability(0,0,0,0,0); }
    public static int wilson(long handled, long eligible) {
        if (handled < 0 || eligible < handled || eligible > 1_000_000_000L) throw fail(Code.INVALID_EVENT);
        if (eligible == 0) return 0;
        double z2 = 1.96 * 1.96, n = eligible, p = handled / n;
        double lower = (p + z2 / (2*n) - 1.96 * StrictMath.sqrt(p*(1-p)/n + z2/(4*n*n))) / (1+z2/n);
        return (int) StrictMath.floor(StrictMath.max(0,StrictMath.min(1,lower))*10_000);
    }
    Reliability add(ReliabilityObservation observation) {
        long h=handled, e=eligible, a=assisted, s=safetyFailures;
        switch (observation) {
            case SUCCESS, EXPECTED_OUTCOME -> { h++; e++; }
            case RECOVERABLE_FAILURE -> e++;
            case POLICY_BLOCK, HARD_FAILURE -> { e++; s++; }
            case HUMAN_ASSISTED -> a++;
        }
        return new Reliability(h,e,a,s,wilson(h,e));
    }
}
