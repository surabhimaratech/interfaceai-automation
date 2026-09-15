package com.surabhimarathe.interfaceautomation.approval;

import java.time.Instant;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalException.*;

public record GovernanceEvent(int journalSchemaVersion, ArtifactIdentity identity, Type type,
        ReliabilityObservation observation, SuspensionReason suspensionReason, long revision,
        Instant timestamp, String actorHash, ApprovalCriteria approvalCriteria) {
    public enum Type { REGISTER, OBSERVE, APPROVE, SUSPEND }
    public GovernanceEvent {
        if (journalSchemaVersion != 1 || identity == null || type == null || timestamp == null
                || revision < 1 || revision > 1_000_000_000L
                || (type == Type.OBSERVE) != (observation != null)
                || (type == Type.SUSPEND) != (suspensionReason != null)
                || (type == Type.APPROVE ? !Hashes.digest(actorHash) : actorHash != null)
                || (type == Type.APPROVE) != (approvalCriteria != null))
            throw fail(Code.INVALID_EVENT);
    }
    @Override public String toString() { return "GovernanceEvent[type="+type+", revision="+revision+", identities=REDACTED]"; }
}
