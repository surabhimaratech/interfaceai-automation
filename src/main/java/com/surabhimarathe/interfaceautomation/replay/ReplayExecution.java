package com.surabhimarathe.interfaceautomation.replay;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.surabhimarathe.interfaceautomation.artifact.*;
import com.surabhimarathe.interfaceautomation.discovery.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import static com.surabhimarathe.interfaceautomation.replay.ReplayResult.Code.*;

/** One invocation, one owner thread, no action retries. All UI access is via Playwright. */
final class ReplayExecution {
    private final CapabilityArtifact artifact;
    private final Map<String, Object> inputs;
    private final ActionPolicy policy;
    private final ReplayOptions options;
    private final ReplayEngine.BrowserLauncher launcher;
    private final long deadline;
    private final AtomicInteger step;
    private long stepDeadline;
    private Page page;
    private BrowserPolicyGuard guard;
    private final ReplayDiagnostics diagnostics;
    private final SessionExpiryMarker expiryMarker;
    private final ReplayHandoff handoff;
    private int handoffs;
    ReplayExecution(CapabilityArtifact artifact, Map<String, Object> inputs, ActionPolicy policy,
                    ReplayOptions options, ReplayEngine.BrowserLauncher launcher, long deadline, AtomicInteger step, ReplayDiagnostics diagnostics, SessionExpiryMarker expiryMarker, ReplayHandoff handoff) {
        this.artifact = artifact; this.inputs = inputs; this.policy = policy; this.options = options;
        this.launcher = launcher; this.deadline = deadline; this.step = step;
        this.stepDeadline = deadline;
        this.diagnostics = diagnostics;
        this.expiryMarker = expiryMarker; this.handoff = handoff;
    }

    ReplayResult run(String entry) {
        try (Playwright playwright = Playwright.create()) {
            double launchTimeout = remaining();
            try (Browser browser = launcher.launch(playwright, new BrowserType.LaunchOptions()
                    .setHeadless(options.headless()).setTimeout(launchTimeout))) {
                remaining();
                var context = browser.newContext(new Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK));
                guard = new BrowserPolicyGuard(context, policy, diagnostics::deniedRequest);
                page = context.newPage();
                page.onDialog(d -> { diagnostics.dialog(); d.dismiss(); });
                context.onPage(p -> { if (p != page) { diagnostics.popup(); p.close(); } });
                diagnostics.phase(ReplayDiagnostic.Phase.LOAD);
                beginStep();
                page.navigate(entry);
                authorizedPage();
                for (StepSpec spec : artifact.steps()) {
                    diagnostics.step(step.incrementAndGet(), spec.id());
                    // Only an unstarted action with a verified expiry marker can return false.
                    while (!performAction(spec)) transfer(spec);
                    authorizedPage();
                    businessOutcome();
                    diagnostics.phase(ReplayDiagnostic.Phase.POSTCONDITION);
                    verify(spec.postcondition(), POSTCONDITION_FAILED);
                }
                beginStep();
                businessOutcome();
                marker();
                Map<String, Object> output = extract();
                businessOutcome();
                marker();
                remaining();
                diagnostics.phase(ReplayDiagnostic.Phase.CLEANUP);
                return new ReplayResult(ReplayResult.Status.SUCCEEDED, CHECKPOINT_VERIFIED, null, step.get(), output);
            }
        } catch (Outcome found) {
            if (diagnostics.safetyCode(null) != null) return failure(BROWSER_FAILURE);
            return new ReplayResult(ReplayResult.Status.EXPECTED_OUTCOME, BUSINESS_OUTCOME, found.code, step.get(), Map.of())
                    .withDiagnostic(diagnostics.snapshot(BUSINESS_OUTCOME));
        } catch (Stop stop) {
            return failure(stop.code);
        } catch (com.microsoft.playwright.TimeoutError ex) {
            return failure(TIMEOUT);
        } catch (RuntimeException ex) {
            return failure(Thread.currentThread().isInterrupted() ? INTERRUPTED :
                    System.nanoTime() >= Math.min(deadline, stepDeadline) ? TIMEOUT : BROWSER_FAILURE);
        }
    }

    private ReplayResult failure(ReplayResult.Code fallback) {
        return diagnostics.failure(fallback);
    }

    private boolean performAction(StepSpec spec) {
        beginStep();
        businessOutcome();
        diagnostics.phase(ReplayDiagnostic.Phase.LOCATOR);
        ElementHandle target = unique(spec.locator());
        try {
            diagnostics.phase(ReplayDiagnostic.Phase.ACTION);
            diagnostics.expected(spec.locator());
            diagnostics.count(1);
            UiAction.Type action = spec.action() == StepSpec.Action.FILL ? UiAction.Type.FILL : UiAction.Type.CLICK;
            remaining();
            if (!guard.permits(action, page, target)) fail(POLICY_DENIED);
            remaining();
            if (sessionExpired()) return false;
            if (!target.isVisible() || !target.isEnabled()) fail(POSTCONDITION_FAILED);
            if (spec.action() == StepSpec.Action.FILL && !target.isEditable()) fail(POLICY_DENIED);
            // Recheck after actionability reads, immediately before dispatch.
            if (sessionExpired()) return false;
            double timeout = remaining();
            diagnostics.actionStarted();
            if (spec.action() == StepSpec.Action.FILL)
                target.fill(fillValue(spec), new ElementHandle.FillOptions().setTimeout(timeout));
            else target.click(new ElementHandle.ClickOptions().setTimeout(timeout));
            return true;
        } finally { target.dispose(); } // Never retain a handle across human ownership.
    }

    private boolean sessionExpired() {
        if (expiryMarker == null) return false;
        remaining();
        var previous = diagnostics.snapshot(null);
        diagnostics.phase(ReplayDiagnostic.Phase.SESSION_CHECK);
        var marker = page.getByRole(AriaRole.valueOf(expiryMarker.role().name()),
                new Page.GetByRoleOptions().setName(expiryMarker.name()).setExact(true));
        int visible = 0;
        int count = marker.count();
        if (count > 200) { diagnostics.count(201); fail(AMBIGUOUS_LOCATOR); }
        for (int i = 0; i < count; i++) { remaining(); if (marker.nth(i).isVisible()) visible++; }
        diagnostics.count(visible);
        remaining();
        if (visible > 1) fail(AMBIGUOUS_LOCATOR);
        diagnostics.restore(previous);
        if (visible == 1) diagnostics.expiry();
        return visible == 1;
    }

    private void transfer(StepSpec spec) {
        authorizedPage();
        if (diagnostics.hasActionStarted()) fail(OWNERSHIP_DENIED);
        diagnostics.phase(ReplayDiagnostic.Phase.HANDOFF);
        diagnostics.expected(spec.locator());
        if (handoff == null) fail(HUMAN_ACTION_REQUIRED);
        if (handoffs >= handoff.limit()) fail(HANDOFF_LIMIT);
        handoffs++;
        diagnostics.beginHandoff();
        handoff.request(step.get());
        long until = Math.min(deadline, System.nanoTime() + handoff.timeout().toNanos());
        while (handoff.status().owner() == HandoffCoordinator.Owner.HUMAN) {
            handoffSafety(until);
            // Dispatch callbacks only: no observation, locator or action while the human owns the UI.
            page.waitForTimeout(Math.max(1, Math.min(50, (until - System.nanoTime()) / 1_000_000)));
        }
        diagnostics.phase(ReplayDiagnostic.Phase.RESUMING);
        handoffSafety(until);
        try { handoff.requireResuming(); }
        catch (IllegalStateException ex) { fail(OWNERSHIP_DENIED); }
        page.waitForTimeout(1);
        handoffSafety(until);
        if (page.isClosed() || !page.context().browser().isConnected()) fail(BROWSER_FAILURE);
        if (!policy.allowsUrl(page.url())) fail(POLICY_DENIED);
        handoff.reclaim();
        diagnostics.endHandoff();
        // The caller restarts only this unstarted step, with a new budget and a fresh handle.
    }

    private void handoffSafety(long until) {
        var unsafe = diagnostics.safetyCode(null);
        if (unsafe != null) fail(unsafe);
        if (Thread.currentThread().isInterrupted()) fail(INTERRUPTED);
        if (System.nanoTime() >= until) fail(HANDOFF_TIMEOUT);
    }

    private String fillValue(StepSpec s) {
        return s.action() == StepSpec.Action.FILL ? ContractValues.text(ContractValues.input(s.inputExpression(), inputs)) : null;
    }

    private void beginStep() {
        stepDeadline = Math.min(deadline, System.nanoTime() + options.stepTimeout().toNanos());
        remaining();
    }

    private double remaining() {
        if (handoff != null) {
            try { handoff.requireAutomation(); }
            catch (IllegalStateException ex) { fail(OWNERSHIP_DENIED); }
        }
        var unsafe = diagnostics.safetyCode(null);
        if (unsafe != null) fail(unsafe);
        if (Thread.currentThread().isInterrupted()) fail(INTERRUPTED);
        long nanos = Math.min(deadline, stepDeadline) - System.nanoTime();
        if (nanos <= 0) fail(TIMEOUT);
        double millis = Math.max(1, nanos / 1_000_000.0);
        if (page != null) {
            page.setDefaultTimeout(millis);
            page.setDefaultNavigationTimeout(millis);
        }
        return millis;
    }

    private void authorizedPage() {
        remaining();
        if (guard.denied() || !policy.allowsUrl(page.url())) fail(POLICY_DENIED);
    }

    private void businessOutcome() {
        authorizedPage();
        var previous = diagnostics.snapshot(null);
        diagnostics.phase(ReplayDiagnostic.Phase.OUTCOME);
        String found = null;
        ReplayDiagnostic foundDiagnostic = null;
        for (OutcomeSpec o : artifact.outcomes()) {
            List<ElementHandle> matches = matches(o.condition().locator());
            try {
                if (matches.size() > 1) fail(AMBIGUOUS_LOCATOR);
                if (matches.size() == 1) {
                    if (found != null) fail(AMBIGUOUS_LOCATOR);
                    found = o.code();
                    foundDiagnostic = diagnostics.snapshot(BUSINESS_OUTCOME);
                }
            } finally { dispose(matches); }
        }
        authorizedPage();
        if (found != null) {
            diagnostics.restore(foundDiagnostic);
            throw new Outcome(found);
        }
        diagnostics.restore(previous);
    }

    private List<ElementHandle> matches(LocatorSpec spec) {
        diagnostics.expected(spec);
        remaining();
        String name = ContractValues.resolve(spec.accessibleName(), inputs);
        var opts = new Page.GetByRoleOptions();
        if (spec.nameMatch() == LocatorSpec.NameMatch.EXACT) opts.setName(name).setExact(true);
        else opts.setName(Pattern.compile("^" + regexLiteral(name)));
        Locator locator = page.getByRole(AriaRole.valueOf(spec.role().name()), opts);
        if (locator.count() > 200) { diagnostics.count(201); fail(AMBIGUOUS_LOCATOR); }
        List<ElementHandle> selected = new ArrayList<>();
        List<ElementHandle> candidates = locator.elementHandles();
        try {
            for (ElementHandle h : candidates) {
                remaining();
                if (!h.isVisible()) continue;
                if (spec.context() != null) {
                    String selector = switch (spec.context().kind()) {
                        case ROW -> "tr,[role=row]";
                        case FORM -> "form,[role=form]";
                        case FIELDSET -> "fieldset";
                    };
                    String text = ContractValues.resolve(spec.context().text(), inputs);
                    boolean inContext = (Boolean) h.evaluate("""
                        (e, args) => {
                            const parent = e.closest(args.selector);
                            if (!parent) return false;
                            const text = parent.innerText.replace(/\\s+/g, ' ').trim();
                            return text.length <= 2000 && text.includes(args.text);
                        }
                        """, Map.of("selector", selector, "text", text));
                    if (!inContext) continue;
                }
                selected.add(h);
            }
            diagnostics.count(selected.size());
            remaining();
            return selected;
        } catch (RuntimeException ex) {
            dispose(selected);
            throw ex;
        } finally {
            for (ElementHandle h : candidates) if (!selected.contains(h)) h.dispose();
        }
    }

    private ElementHandle unique(LocatorSpec spec) {
        List<ElementHandle> found = matches(spec);
        if (found.size() == 1) return found.getFirst();
        dispose(found);
        // Outcomes can appear between the pre-step observation and this failed lookup.
        businessOutcome();
        fail(found.isEmpty() ? ZERO_LOCATOR : AMBIGUOUS_LOCATOR);
        throw new AssertionError();
    }

    private void verify(PostconditionSpec post, ReplayResult.Code failure) {
        List<ElementHandle> found = matches(post.locator());
        try {
            boolean good = found.size() == 1;
            if (good && post.kind() == PostconditionSpec.Kind.VALUE_EQUALS_INPUT)
                good = ContractValues.text(ContractValues.input(post.inputExpression(), inputs)).equals(found.getFirst().inputValue());
            if (!good) {
                businessOutcome();
                fail(failure);
            }
            remaining();
        } finally { dispose(found); }
    }

    private void marker() {
        diagnostics.phase(ReplayDiagnostic.Phase.CHECKPOINT);
        verify(new PostconditionSpec(PostconditionSpec.Kind.VISIBLE, artifact.checkpoint().marker(), null), CHECKPOINT_FAILED);
    }

    private Map<String, Object> extract() {
        diagnostics.phase(ReplayDiagnostic.Phase.EXTRACTION);
        Map<String, Object> values = new LinkedHashMap<>();
        for (ExtractorSpec e : artifact.checkpoint().extractors()) {
            remaining();
            List<ElementHandle> found = matches(e.locator());
            try {
                if (found.size() != 1) fail(EXTRACTION_FAILED);
                ElementHandle source = found.getFirst();
                String text;
                if (e.read() == ExtractorSpec.Read.ROW_VALUE) {
                    List<ElementHandle> cells = source.querySelectorAll(":scope > td, :scope > [role=cell]");
                    try {
                        if (cells.size() != 1 || !cells.getFirst().isVisible()) fail(EXTRACTION_FAILED);
                        text = boundedText(cells.getFirst());
                    } finally { dispose(cells); }
                } else text = boundedText(source);
                Object value;
                try {
                    value = e.format() == ExtractorSpec.Format.USD_DECIMAL ? ContractValues.usd(text) : text;
                    OutputSpec contract = artifact.outputs().get(e.output());
                    if (!ContractValues.valid(contract.type(), contract.constraints(), value)) fail(EXTRACTION_FAILED);
                    if (e.expectedInputExpression() != null
                            && !ContractValues.equal(value, ContractValues.input(e.expectedInputExpression(), inputs))) fail(EXTRACTION_FAILED);
                } catch (IllegalArgumentException ex) { fail(EXTRACTION_FAILED); throw new AssertionError(); }
                values.put(e.output(), value);
            } finally { dispose(found); }
        }
        return values;
    }

    private String boundedText(ElementHandle h) {
        remaining();
        String text = (String) h.evaluate("e => { const t = e.innerText; return t.length <= 1000 ? t : null; }");
        if (text == null) fail(EXTRACTION_FAILED);
        return text;
    }

    private static void dispose(List<ElementHandle> handles) { for (ElementHandle h : handles) h.dispose(); }
    // Playwright transports the pattern to JavaScript, where Java's \Q...\E quoting is not supported.
    private static String regexLiteral(String text) {
        StringBuilder escaped = new StringBuilder();
        for (char c : text.toCharArray()) {
            if ("\\^$.*+?()[]{}|".indexOf(c) >= 0) escaped.append('\\');
            escaped.append(c);
        }
        return escaped.toString();
    }
    private void fail(ReplayResult.Code code) {
        if (code == POLICY_DENIED) diagnostics.deniedPolicy();
        throw new Stop(code);
    }
    private static final class Stop extends RuntimeException {
        final ReplayResult.Code code;
        Stop(ReplayResult.Code code) { super("REPLAY_STOP", null, false, false); this.code = code; }
    }
    private static final class Outcome extends RuntimeException {
        final String code;
        Outcome(String code) { super("REPLAY_OUTCOME", null, false, false); this.code = code; }
    }
}
