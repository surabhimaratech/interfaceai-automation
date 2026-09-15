package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.discovery.HandoffCoordinator;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One invocation. Operator threads signal resume; only the browser thread reclaims ownership. */
public final class ReplayHandoff implements AutoCloseable {
    private final HandoffCoordinator ownership = new HandoffCoordinator();
    private final AtomicInteger transfers = new AtomicInteger();
    int transferCount() { return transfers.get(); }
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final Duration timeout;
    private final int limit;
    public ReplayHandoff(Duration timeout, int limit) {
        if (timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0
                || timeout.compareTo(Duration.ofMinutes(5)) > 0 || limit < 1 || limit > 10)
            throw new IllegalArgumentException("INVALID_HANDOFF_CONFIGURATION");
        this.timeout = timeout; this.limit = limit;
    }
    public HandoffCoordinator.Status status() { return ownership.status(); }
    boolean claim() { return claimed.compareAndSet(false,true); }
    Duration timeout() { return timeout; }
    int limit() { return limit; }
    void requireAutomation() { ownership.requireAutomation(); }
    void requireResuming() {
        if (status().owner() != HandoffCoordinator.Owner.RESUMING) throw new IllegalStateException("INVALID_OWNERSHIP");
    }
    void request(int step) {
        try { ownership.request(HandoffCoordinator.Reason.SESSION_EXPIRY,step); transfers.incrementAndGet(); }
        catch (IOException ex) { throw new IllegalStateException("HANDOFF_FAILED"); }
    }
    // Transport only: never rendered, serialized, logged, or exposed to the target page.
    String transportToken() { return ownership.resumeToken(); }
    boolean resume(String token) {
        try { return ownership.resume(token); }
        catch (IOException ex) { return false; }
    }
    void reclaim() {
        requireResuming();
        try { ownership.reclaim(); }
        catch (IOException ex) { throw new IllegalStateException("HANDOFF_FAILED"); }
    }
    @Override public void close() {
        try { ownership.close(); }
        catch (IOException ex) { throw new IllegalStateException("HANDOFF_FAILED"); }
    }
    @Override public String toString() { return "ReplayHandoff[owner=" + status().owner() + "]"; }
}
