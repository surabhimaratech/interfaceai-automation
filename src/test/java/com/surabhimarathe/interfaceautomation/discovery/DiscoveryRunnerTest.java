package com.surabhimarathe.interfaceautomation.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryRunnerTest {
    @TempDir Path temp;
    static final ReviewCheckpoint.Request REQUEST = new ReviewCheckpoint.Request("100042", "SAV-2048", new BigDecimal("25"), "Courtesy adjustment");
    static Observation review(String amount) {
        return new Observation(UUID.randomUUID(), "http://localhost/legacy/accounts/SAV-2048/fee-reversal/review",
            "Fee Reversal Review", List.of(), List.of("Ready for review — not submitted."), List.of(),
            Map.of("Member ID", "100042", "Name", "Morgan Lee", "Account", "SAV-2048",
                "Current balance", "$1842.73", "Reversal amount", amount, "Reason", "Courtesy adjustment",
                "Projected balance", "$1867.73"));
    }
    static class Surface implements DiscoverySurface {
        int actions, observations; Observation next;
        boolean sameId;
        Surface(Observation next) { this.next = next; }
        public void budget(Duration remaining) { assertTrue(remaining.isPositive()); }
        public void waitBriefly() {}
        public Observation open(String url) { return next; }
        public Observation observe() {
            observations++;
            return new Observation(sameId ? next.id() : UUID.randomUUID(), next.url(), next.heading(), next.controls(), next.statuses(), next.alerts(), next.details());
        }
        public ActionResult execute(UiAction action) {
            actions++;
            return new ActionResult(ActionResult.Status.SUCCEEDED, ActionResult.Code.VALUE_VERIFIED, next);
        }
    }
    DiscoveryRunner runner(Surface surface, DecisionClient client, int max, AtomicLong clock) throws Exception {
        return new DiscoveryRunner(surface, client, new SafeEvents(temp.resolve(UUID.randomUUID()+".jsonl")),
            max, Duration.ofSeconds(1), clock::get);
    }
    static UiAction decision(Observation o, UiAction.Type type) { return new UiAction(o.id(), type, "", ""); }
    @Test void maximumStepsCountsWaitsAndStopsModelCalls() throws Exception {
        Surface s = new Surface(new Observation(UUID.randomUUID(), "/legacy", "Member Search", List.of()));
        var result = runner(s, (g,o,t) -> decision(o, UiAction.Type.WAIT), 2, new AtomicLong()).run("/legacy", REQUEST);
        assertEquals(RunState.LIMIT_REACHED, result.state());
        assertEquals(2, result.steps());
        assertEquals(0, s.actions);
    }
    @Test void lateModelResponseNeverExecutesAnAction() throws Exception {
        AtomicLong clock = new AtomicLong();
        Surface s = new Surface(new Observation(UUID.randomUUID(), "/legacy", "", List.of(new Observation.Control("c0", "textbox", "Member ID"))));
        var result = runner(s, (g,o,t) -> {
            clock.set(Duration.ofSeconds(2).toNanos());
            return new UiAction(o.id(), UiAction.Type.FILL, "c0", "100042");
        }, 5, clock).run("/legacy", REQUEST);
        assertEquals(RunState.TIMED_OUT, result.state()); assertEquals(0, s.actions);
    }
    @Test void rejectsInvalidAndStaleModelTargets() throws Exception {
        Surface s = new Surface(new Observation(UUID.randomUUID(), "/legacy", "", List.of()));
        var result = runner(s, (g,o,t) -> new UiAction(o.id(), UiAction.Type.CLICK, "c99", ""), 5, new AtomicLong()).run("/legacy", REQUEST);
        assertEquals(ActionResult.Code.INVALID_MODEL_ACTION, result.code()); assertEquals(0, s.actions);
        result = runner(s, (g,o,t) -> new UiAction(UUID.randomUUID(), UiAction.Type.COMPLETE, "", ""), 5, new AtomicLong()).run("/legacy", REQUEST);
        assertEquals(ActionResult.Code.STALE_OBSERVATION, result.code());
        assertThrows(IllegalArgumentException.class, () -> new UiAction(UUID.randomUUID(), UiAction.Type.WAIT, "c0", ""));
    }
    @Test void recognizesBusinessOutcomeWithoutCallingModel() throws Exception {
        for (boolean validation : List.of(false, true)) {
            Surface s = new Surface(new Observation(UUID.randomUUID(), "/legacy", "", List.of(),
                validation ? List.of() : List.of("Member not found MEMBER_NOT_FOUND"),
                validation ? List.of("VALIDATION_REJECTED Enter a valid amount") : List.of(), Map.of()));
            var result = runner(s, (g,o,t) -> { fail("Must not call model"); return null; }, 5, new AtomicLong()).run("/legacy", REQUEST);
            assertEquals(RunState.BUSINESS_OUTCOME, result.state());
            assertEquals(validation ? ActionResult.Code.VALIDATION_REJECTED : ActionResult.Code.MEMBER_NOT_FOUND, result.code());
        }
    }
    @Test void completionRequiresFreshVerifiedDetails() throws Exception {
        Surface s = new Surface(review("$25.00"));
        var result = runner(s, (g,o,t) -> decision(o, UiAction.Type.COMPLETE), 5, new AtomicLong()).run("/legacy", REQUEST);
        assertEquals(RunState.SUCCEEDED, result.state());
        assertEquals(new BigDecimal("1867.73"), result.details().projectedBalance());
        assertEquals("Courtesy adjustment", result.details().reason());
        assertEquals(2, s.observations);
        s.next = review("$24.00");
        result = runner(s, (g,o,t) -> decision(o, UiAction.Type.COMPLETE), 5, new AtomicLong()).run("/legacy", REQUEST);
        assertEquals(ActionResult.Code.CHECKPOINT_MISMATCH, result.code());
        assertNull(result.details());
        s.next = review("$25.00"); s.sameId = true;
        result = runner(s, (g,o,t) -> decision(o, UiAction.Type.COMPLETE), 5, new AtomicLong()).run("/legacy", REQUEST);
        assertEquals(ActionResult.Code.STALE_OBSERVATION, result.code());
    }
    @Test void prematureCompletionFailsAndHumanRequestStops() throws Exception {
        Surface s = new Surface(new Observation(UUID.randomUUID(), "/legacy", "Member Search", List.of()));
        var result = runner(s, (g,o,t) -> decision(o, UiAction.Type.COMPLETE), 5, new AtomicLong()).run("/legacy", REQUEST);
        assertEquals(ActionResult.Code.CHECKPOINT_MISMATCH, result.code());
        result = runner(s, (g,o,t) -> decision(o, UiAction.Type.REQUEST_HUMAN), 5, new AtomicLong()).run("/legacy", REQUEST);
        assertEquals(RunState.HUMAN_REQUIRED, result.state()); assertEquals(0, s.actions);
    }
    @Test void providerCreditErrorIsSanitizedAndStopsBeforeAction() throws Exception {
        Surface s = new Surface(new Observation(UUID.randomUUID(), "/legacy", "Member Search", List.of()));
        var result = runner(s, (g,o,t) -> { throw new OpenRouterClient.ModelFailure("MODEL_HTTP_402"); }, 5, new AtomicLong()).run("/legacy", REQUEST);
        assertEquals(ActionResult.Code.MODEL_HTTP_402, result.code());
        assertEquals(RunState.FAILED, result.state()); assertEquals(0, s.actions);
    }
    @Test void sanitizesContextBeforeTruncation() {
        String cleaned = ContextText.clean("token=private-value person@example.org 123-45-6789 " + "a".repeat(600), 300);
        assertEquals(300, cleaned.length());
        assertFalse(cleaned.contains("private-value")); assertFalse(cleaned.contains("person@")); assertFalse(cleaned.contains("123-45"));
    }
    @Test void passesPreviousSummaryAndStopsRepeatedActions() throws Exception {
        Surface s = new Surface(new Observation(UUID.randomUUID(), "/legacy", "Search", List.of(new Observation.Control("c0", "textbox", "Member ID"))));
        java.util.List<PreviousAction> summaries = new java.util.ArrayList<>();
        DecisionClient client = new DecisionClient() {
            public UiAction decide(String g, Observation o, Duration t) { throw new AssertionError(); }
            public UiAction decide(String g, Observation o, Duration t, PreviousAction p) {
                summaries.add(p); return new UiAction(o.id(), UiAction.Type.FILL, "c0", "100042");
            }
        };
        var result = runner(s, client, 10, new AtomicLong()).run("/legacy", REQUEST);
        assertEquals(ActionResult.Code.NO_PROGRESS, result.code()); assertEquals(2, s.actions);
        assertNull(summaries.getFirst());
        assertEquals(new PreviousAction(UiAction.Type.FILL, ActionResult.Code.VALUE_VERIFIED, false), summaries.get(1));
    }
    @Test void modelHumanRequestResumesWithFreshObservationAndCompletes() throws Exception {
        var h = new HandoffCoordinator();
        Surface s = new Surface(new Observation(UUID.randomUUID(), "/legacy", "Search", List.of())) {
            public void giveToHuman(HandoffCoordinator.Reason reason, int step) {
                try { h.request(reason, step); } catch (Exception e) { throw new RuntimeException(e); }
            }
            public void pumpHumanEvents() {
                assertEquals(HandoffCoordinator.Owner.HUMAN, h.status().owner());
                next = review("$25.00"); h.interaction();
                try { h.resume(h.resumeToken()); } catch (Exception e) { throw new RuntimeException(e); }
            }
            public void reclaimFromHuman() {
                try { h.reclaim(); } catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        var result = runner(s, (g,o,t) -> {
            h.requireAutomation();
            return decision(o, o.heading().equals("Search") ? UiAction.Type.REQUEST_HUMAN : UiAction.Type.COMPLETE);
        }, 5, new AtomicLong()).handoff(h, Duration.ofSeconds(1), false, 3).run("/legacy", REQUEST);
        assertEquals(RunState.SUCCEEDED, result.state()); assertEquals(2, result.steps());
        assertEquals(HandoffCoordinator.Owner.AUTOMATION, h.status().owner()); assertEquals(0, s.actions);
    }
    @Test void humanWaitIsBoundedWithoutFurtherModelCalls() throws Exception {
        var h = new HandoffCoordinator(); var clock = new AtomicLong();
        Surface s = new Surface(new Observation(UUID.randomUUID(), "/legacy", "Search", List.of())) {
            public void giveToHuman(HandoffCoordinator.Reason reason, int step) {
                try { h.request(reason, step); } catch (Exception e) { throw new RuntimeException(e); }
            }
            public void pumpHumanEvents() { clock.addAndGet(Duration.ofMillis(200).toNanos()); }
        };
        var result = runner(s, (g,o,t) -> decision(o, UiAction.Type.REQUEST_HUMAN), 5, clock)
            .handoff(h, Duration.ofMillis(100), false, 3).run("/legacy", REQUEST);
        assertEquals(ActionResult.Code.HANDOFF_TIMEOUT, result.code()); assertEquals(1, result.steps());
    }
}
