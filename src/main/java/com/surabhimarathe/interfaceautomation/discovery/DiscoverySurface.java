package com.surabhimarathe.interfaceautomation.discovery;

import java.time.Duration;

public interface DiscoverySurface {
    Observation open(String url);
    Observation observe();
    ActionResult execute(UiAction action);
    void budget(Duration remaining);
    void waitBriefly();
    default void bindRun(java.util.UUID runId, boolean correlatedFilename) { }
    default void discoveryFinished(DiscoveryRunner.Result result) { }
    default void attachHandoff(HandoffCoordinator coordinator) { }
    default void simulateExpiry() { throw new UnsupportedOperationException(); }
    default void giveToHuman(HandoffCoordinator.Reason reason, int step) { throw new UnsupportedOperationException(); }
    default void pumpHumanEvents() { throw new UnsupportedOperationException(); }
    default void reclaimFromHuman() { throw new UnsupportedOperationException(); }
}
