package com.surabhimarathe.interfaceautomation.approval;

import com.surabhimarathe.interfaceautomation.replay.TenantId;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalException.*;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalState.Lifecycle.*;

/** Trusted host operations, not authentication and not replay enforcement. */
public final class ApprovalService {
    private final GovernanceStore store;
    private final ApprovalPolicy policy;
    private final Clock clock;
    public ApprovalService(GovernanceStore store, ApprovalPolicy policy, Clock clock) {
        if (store == null || policy == null || clock == null) throw fail(Code.INVALID_POLICY);
        this.store = store; this.policy = policy; this.clock = clock;
    }
    public ApprovalState register(TenantId tenant, byte[] exactJson) {
        var id = ArtifactIdentity.from(tenant,exactJson);
        return mutate(id,history -> {
            if (GovernanceHistory.validate(history).containsKey(id)) throw fail(Code.INVALID_TRANSITION);
            return event(id,GovernanceEvent.Type.REGISTER,null,null,null,history.size()+1L);
        });
    }
    public ApprovalState state(ArtifactIdentity id) { return require(store.read(),id); }
    public boolean eligible(ArtifactIdentity id) {
        var state = state(id);
        return state.lifecycle() == DRAFT && policy.eligible(state.reliability());
    }
    public ApprovalState observe(ArtifactIdentity id, ReliabilityObservation observation) {
        if (observation == null) throw fail(Code.INVALID_EVENT);
        return mutate(id,history -> {
            if (require(history,id).lifecycle() == SUSPENDED) throw fail(Code.INVALID_TRANSITION);
            return event(id,GovernanceEvent.Type.OBSERVE,observation,null,null,history.size()+1L);
        });
    }
    public ApprovalState approve(ArtifactIdentity id, String trustedHumanActor) {
        if (trustedHumanActor == null || trustedHumanActor.isBlank() || trustedHumanActor.length() > 160
                || trustedHumanActor.codePoints().anyMatch(c -> Character.isISOControl(c) || (c >= 0xD800 && c <= 0xDFFF)))
            throw fail(Code.INVALID_ACTOR);
        String actorHash = Hashes.scoped("interfaceai:approval:actor:v1",trustedHumanActor);
        return mutate(id,history -> {
            var state = require(history,id);
            if (state.lifecycle() != DRAFT) throw fail(Code.INVALID_TRANSITION);
            if (!policy.eligible(state.reliability())) throw fail(Code.NOT_ELIGIBLE);
            return event(id,GovernanceEvent.Type.APPROVE,null,null,actorHash,history.size()+1L);
        });
    }
    public ApprovalState suspend(ArtifactIdentity id, SuspensionReason reason) {
        if (reason == null) throw fail(Code.INVALID_EVENT);
        return mutate(id,history -> {
            if (require(history,id).lifecycle() != APPROVED) throw fail(Code.INVALID_TRANSITION);
            return event(id,GovernanceEvent.Type.SUSPEND,null,reason,null,history.size()+1L);
        });
    }
    private GovernanceEvent event(ArtifactIdentity id, GovernanceEvent.Type type, ReliabilityObservation observation,
            SuspensionReason reason, String actor, long revision) {
        try { return new GovernanceEvent(1,id,type,observation,reason,revision,clock.instant(),actor,
                type == GovernanceEvent.Type.APPROVE ? ApprovalCriteria.from(policy) : null); }
        catch (ApprovalException ex) { throw ex; }
        catch (RuntimeException ex) { throw fail(Code.INVALID_EVENT); }
    }
    private ApprovalState mutate(ArtifactIdentity id, Function<List<GovernanceEvent>,GovernanceEvent> mutation) {
        return require(store.transact(mutation),id);
    }
    private ApprovalState require(List<GovernanceEvent> history, ArtifactIdentity id) {
        var state = GovernanceHistory.validate(history).get(id);
        if (state == null) throw fail(Code.UNKNOWN_IDENTITY);
        return state;
    }
    @Override public String toString() { return "ApprovalService[REDACTED]"; }
}
