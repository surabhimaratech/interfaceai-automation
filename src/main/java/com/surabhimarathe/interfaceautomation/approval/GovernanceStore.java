package com.surabhimarathe.interfaceautomation.approval;

import java.util.List;
import java.util.function.Function;

/** Trusted host boundary. Transactions run under the store's in-process lock. */
public interface GovernanceStore {
    List<GovernanceEvent> read();
    List<GovernanceEvent> transact(Function<List<GovernanceEvent>,GovernanceEvent> mutation);
}
