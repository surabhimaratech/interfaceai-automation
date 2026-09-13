package com.surabhimarathe.interfaceautomation.discovery;

import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;
import static com.surabhimarathe.interfaceautomation.discovery.ActionResult.Code.*;

/** Bounded decisions; the model chooses navigation, the host verifies completion. */
public final class DiscoveryRunner {
    public record Result(RunState state, ActionResult.Code code, int steps, ReviewCheckpoint.Details details) {}
    private final DiscoverySurface surface;
    private final DecisionClient model;
    private final SafeEvents events;
    private final int maxSteps;
    private final Duration timeout;
    private final LongSupplier clock;
    private RunState state = RunState.CREATED;
    private int steps;
    private long started;
    private final Set<UUID> observations = new HashSet<>();

    public DiscoveryRunner(DiscoverySurface surface, DecisionClient model, SafeEvents events, int maxSteps, Duration timeout) {
        this(surface, model, events, maxSteps, timeout, System::nanoTime);
    }
    DiscoveryRunner(DiscoverySurface surface, DecisionClient model, SafeEvents events, int maxSteps, Duration timeout, LongSupplier clock) {
        if (maxSteps < 1 || maxSteps > 100 || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("INVALID_LIMITS");
        this.surface = surface; this.model = model; this.events = events;
        this.maxSteps = maxSteps; this.timeout = timeout; this.clock = clock;
    }
    public Result run(String entry, ReviewCheckpoint.Request request) throws java.io.IOException {
        if (state != RunState.CREATED) throw new IllegalStateException("RUN_ALREADY_STARTED");
        started = clock.getAsLong();
        try {
            transition(RunState.OBSERVING, null, null, false);
            surface.budget(remaining());
            surface.open(entry);
            while (true) {
                if (expired()) return end(RunState.TIMED_OUT, DEADLINE_EXCEEDED, null);
                if (steps >= maxSteps) return end(RunState.LIMIT_REACHED, MAX_STEPS, null);
                surface.budget(remaining());
                Observation observed = surface.observe();
                if (expired()) return end(RunState.TIMED_OUT, DEADLINE_EXCEEDED, null);
                if (!observations.add(observed.id())) return end(RunState.BLOCKED, STALE_OBSERVATION, null);
                if (observed.statuses().stream().anyMatch(s -> s.contains("MEMBER_NOT_FOUND")))
                    return end(RunState.BUSINESS_OUTCOME, MEMBER_NOT_FOUND, null);
                if (observed.alerts().stream().anyMatch(s -> s.contains("VALIDATION_REJECTED")))
                    return end(RunState.BUSINESS_OUTCOME, VALIDATION_REJECTED, null);
                if (!observed.alerts().isEmpty())
                    return end(RunState.HUMAN_REQUIRED, HUMAN_REQUESTED, null);
                transition(RunState.DECIDING, null, null, false);
                steps++;
                UiAction decision = model.decide(request.goal(), observed, remaining());
                if (expired()) return end(RunState.TIMED_OUT, DEADLINE_EXCEEDED, null);
                if (decision == null || !decision.observationId().equals(observed.id()))
                    return end(RunState.BLOCKED, STALE_OBSERVATION, null);
                if (!decision.controlId().isEmpty() && observed.controls().stream().noneMatch(c -> c.id().equals(decision.controlId())))
                    return end(RunState.FAILED, INVALID_MODEL_ACTION, null);
                surface.budget(remaining());
                switch (decision.type()) {
                    case REQUEST_HUMAN -> { return end(RunState.HUMAN_REQUIRED, HUMAN_REQUESTED, null); }
                    case COMPLETE -> {
                        transition(RunState.VERIFYING, decision.type(), null, model.live());
                        var fresh = surface.observe();
                        if (expired()) return end(RunState.TIMED_OUT, DEADLINE_EXCEEDED, null);
                        if (!observations.add(fresh.id())) return end(RunState.BLOCKED, STALE_OBSERVATION, null);
                        var details = ReviewCheckpoint.verify(fresh, request);
                        return end(details == null ? RunState.FAILED : RunState.SUCCEEDED,
                            details == null ? CHECKPOINT_MISMATCH : CHECKPOINT_VERIFIED, details);
                    }
                    case WAIT -> {
                        transition(RunState.WAITING, decision.type(), null, model.live());
                        surface.waitBriefly();
                        transition(RunState.OBSERVING, decision.type(), WAIT_COMPLETED, model.live());
                    }
                    case FILL, CLICK -> {
                        transition(RunState.ACTING, decision.type(), null, model.live());
                        var result = surface.execute(decision);
                        if (expired()) return end(RunState.TIMED_OUT, DEADLINE_EXCEEDED, null);
                        if (result.status() != ActionResult.Status.SUCCEEDED)
                            return end(result.status() == ActionResult.Status.BLOCKED ? RunState.BLOCKED : RunState.FAILED, result.code(), null);
                        transition(RunState.OBSERVING, decision.type(), result.code(), model.live());
                    }
                }
            }
        } catch (OpenRouterClient.ModelFailure e) {
            ActionResult.Code code = MODEL_FAILURE;
            try { code = ActionResult.Code.valueOf(e.getMessage()); } catch (IllegalArgumentException ignored) { }
            return end(expired() ? RunState.TIMED_OUT : RunState.FAILED, expired() ? DEADLINE_EXCEEDED : code, null);
        } catch (RuntimeException e) {
            return end(expired() ? RunState.TIMED_OUT : RunState.FAILED, expired() ? DEADLINE_EXCEEDED : INVALID_MODEL_ACTION, null);
        }
    }
    private boolean expired() { return clock.getAsLong() - started >= timeout.toNanos(); }
    private Duration remaining() { return Duration.ofNanos(Math.max(1, timeout.toNanos() - (clock.getAsLong() - started))); }
    private Result end(RunState terminal, ActionResult.Code code, ReviewCheckpoint.Details details) throws java.io.IOException {
        transition(terminal, null, code, false);
        return new Result(state, code, steps, details);
    }
    private void transition(RunState next, UiAction.Type action, ActionResult.Code code, boolean live) throws java.io.IOException {
        boolean terminal = Set.of(RunState.SUCCEEDED, RunState.FAILED, RunState.BLOCKED, RunState.TIMED_OUT,
            RunState.LIMIT_REACHED, RunState.BUSINESS_OUTCOME, RunState.HUMAN_REQUIRED).contains(next);
        boolean allowed = terminal || switch (state) {
            case CREATED, ACTING, WAITING -> next == RunState.OBSERVING;
            case OBSERVING -> next == RunState.DECIDING;
            case DECIDING -> Set.of(RunState.ACTING, RunState.WAITING, RunState.VERIFYING).contains(next);
            default -> false;
        };
        if (!allowed) throw new IllegalStateException("INVALID_TRANSITION");
        state = next;
        events.recordStep(state, action, code, live, steps);
    }
}
