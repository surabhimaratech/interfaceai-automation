package com.surabhimarathe.interfaceautomation.approval;

import java.util.List;
import java.util.function.Function;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalException.*;

public final class InMemoryGovernanceStore implements GovernanceStore {
    private List<GovernanceEvent> events = List.of();
    private boolean mutating;
    @Override public synchronized List<GovernanceEvent> read() {
        GovernanceHistory.validate(events); return events;
    }
    @Override public synchronized List<GovernanceEvent> transact(Function<List<GovernanceEvent>,GovernanceEvent> mutation) {
        if (mutation == null || mutating) throw fail(Code.INVALID_EVENT);
        mutating = true;
        try { events = GovernanceHistory.append(read(),mutation.apply(events)); return events; }
        finally { mutating = false; }
    }
    @Override public String toString() { return "InMemoryGovernanceStore[REDACTED]"; }
}
