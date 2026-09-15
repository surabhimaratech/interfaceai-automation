package com.surabhimarathe.interfaceautomation.approval;

import java.time.Instant;
import java.util.*;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalException.*;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalState.Lifecycle.*;

/** Rebuild from metadata only; never trust cached counters or a serialized state. */
final class GovernanceHistory {
    static final int MAX_EVENTS = 100_000;
    static Map<ArtifactIdentity,ApprovalState> validate(List<GovernanceEvent> events) {
        if (events == null || events.size() > MAX_EVENTS) throw fail(Code.CORRUPT_HISTORY);
        Map<ArtifactIdentity,ApprovalState> states = new LinkedHashMap<>();
        Map<String,ArtifactIdentity> digests = new HashMap<>();
        Instant previous = null;
        long revision = 0;
        for (GovernanceEvent e : events) {
            if (e == null || e.revision() != ++revision || (previous != null && e.timestamp().isBefore(previous)))
                throw fail(Code.CORRUPT_HISTORY);
            previous = e.timestamp();
            var id = e.identity();
            String key = id.tenantScopeHash()+":"+id.artifactDigest();
            var existing = digests.putIfAbsent(key,id);
            if (existing != null && !existing.equals(id)) throw fail(Code.CORRUPT_HISTORY);
            var state = states.get(id);
            switch (e.type()) {
                case REGISTER -> {
                    if (state != null) throw fail(Code.CORRUPT_HISTORY);
                    state = new ApprovalState(id,DRAFT,Reliability.empty(),null,null,null,null);
                }
                case OBSERVE -> {
                    if (state == null || state.lifecycle() == SUSPENDED) throw fail(Code.CORRUPT_HISTORY);
                    state = new ApprovalState(id,state.lifecycle(),state.reliability().add(e.observation()),
                            state.approvedAt(),state.approverHash(),null,state.approvedUnder());
                }
                case APPROVE -> {
                    if (state == null || state.lifecycle() != DRAFT
                            || !e.approvalCriteria().eligible(state.reliability())) throw fail(Code.CORRUPT_HISTORY);
                    state = new ApprovalState(id,APPROVED,state.reliability(),e.timestamp(),e.actorHash(),null,e.approvalCriteria());
                }
                case SUSPEND -> {
                    if (state == null || state.lifecycle() != APPROVED) throw fail(Code.CORRUPT_HISTORY);
                    state = new ApprovalState(id,SUSPENDED,state.reliability(),state.approvedAt(),state.approverHash(),e.suspensionReason(),state.approvedUnder());
                }
            }
            states.put(id,state);
        }
        return Collections.unmodifiableMap(states);
    }
    static List<GovernanceEvent> append(List<GovernanceEvent> history, GovernanceEvent next) {
        if (history.size() >= MAX_EVENTS) throw fail(Code.LIMIT_REACHED);
        var copy = new ArrayList<>(history);
        copy.add(next); validate(copy);
        return List.copyOf(copy);
    }
}
