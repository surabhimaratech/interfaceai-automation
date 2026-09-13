package com.surabhimarathe.interfaceautomation.discovery;

import java.io.IOException;
import java.util.UUID;

/** One local operator. HTTP threads signal only; the browser thread grants/reclaims ownership. */
public final class HandoffCoordinator {
    public enum Owner { AUTOMATION, HUMAN, RESUMING, CLOSED }
    public enum Reason { MODEL_REQUEST, SIMULATED_SESSION_EXPIRY, UNEXPECTED_ALERT, NO_PROGRESS }
    public record Status(Owner owner, Reason reason, int step, long epoch, int interactions) {}
    private Owner owner = Owner.AUTOMATION;
    private Reason reason;
    private int step, interactions;
    private long epoch;
    private String token;
    private SafeEvents events;

    public synchronized void attach(SafeEvents events) { this.events = events; }
    public synchronized Status status() { return new Status(owner, reason, step, epoch, interactions); }
    public synchronized String resumeToken() { return owner == Owner.HUMAN ? token : ""; }
    public synchronized void requireAutomation() {
        if (owner != Owner.AUTOMATION) throw new IllegalStateException("HUMAN_OWNS_SESSION");
    }
    public synchronized void request(Reason why, int currentStep) throws IOException {
        requireAutomation();
        reason = why; step = currentStep; interactions = 0; epoch++;
        token = UUID.randomUUID().toString();
        transfer(Owner.HUMAN);
    }
    public synchronized void interaction() {
        if (owner == Owner.HUMAN) interactions = Math.min(10000, interactions + 1);
    }
    public synchronized boolean resume(String suppliedToken) throws IOException {
        if (owner != Owner.HUMAN || token == null || !token.equals(suppliedToken)) return false;
        transfer(Owner.RESUMING); token = null;
        return true;
    }
    public synchronized void reclaim() throws IOException {
        if (owner != Owner.RESUMING) throw new IllegalStateException("NO_RESUME_SIGNAL");
        transfer(Owner.AUTOMATION);
    }
    public synchronized void close() throws IOException {
        if (owner != Owner.CLOSED) transfer(Owner.CLOSED);
        token = null;
    }
    private void transfer(Owner next) throws IOException {
        if (events != null) events.control(owner, next, reason, step, epoch, interactions);
        owner = next;
    }
}
