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
    private boolean dialog;
    ReplayExecution(CapabilityArtifact artifact, Map<String, Object> inputs, ActionPolicy policy,
                    ReplayOptions options, ReplayEngine.BrowserLauncher launcher, long deadline, AtomicInteger step) {
        this.artifact = artifact; this.inputs = inputs; this.policy = policy; this.options = options;
        this.launcher = launcher; this.deadline = deadline; this.step = step;
        this.stepDeadline = deadline;
    }

    ReplayResult run(String entry) {
        try (Playwright playwright = Playwright.create()) {
            double launchTimeout = remaining();
            try (Browser browser = launcher.launch(playwright, new BrowserType.LaunchOptions()
                    .setHeadless(options.headless()).setTimeout(launchTimeout))) {
                remaining();
                var context = browser.newContext(new Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK));
                guard = new BrowserPolicyGuard(context, policy);
                page = context.newPage();
                page.onDialog(d -> { dialog = true; d.dismiss(); });
                context.onPage(p -> { if (p != page) { dialog = true; p.close(); } });
                beginStep();
                page.navigate(entry);
                authorizedPage();
                for (StepSpec spec : artifact.steps()) {
                    step.incrementAndGet();
                    beginStep();
                    businessOutcome();
                    ElementHandle target = unique(spec.locator());
                    try {
                        // Use the resolved handle, not a lazy locator that could retarget after authorization.
                        UiAction.Type action = spec.action() == StepSpec.Action.FILL ? UiAction.Type.FILL : UiAction.Type.CLICK;
                        remaining();
                        if (!guard.permits(action, page, target)) fail(POLICY_DENIED);
                        remaining();
                        if (!target.isVisible() || !target.isEnabled()) fail(POSTCONDITION_FAILED);
                        if (spec.action() == StepSpec.Action.FILL) {
                            if (!target.isEditable()) fail(POLICY_DENIED);
                            target.fill(fillValue(spec), new ElementHandle.FillOptions().setTimeout(remaining()));
                        } else target.click(new ElementHandle.ClickOptions().setTimeout(remaining()));
                    } finally { target.dispose(); }
                    authorizedPage();
                    businessOutcome(); // Before the success postcondition, including on HTTP 200 business errors.
                    verify(spec.postcondition(), POSTCONDITION_FAILED);
                }
                beginStep();
                businessOutcome();
                marker();
                Map<String, Object> output = extract();
                businessOutcome();
                marker();
                remaining();
                return new ReplayResult(ReplayResult.Status.SUCCEEDED, CHECKPOINT_VERIFIED, null, step.get(), output);
            }
        } catch (Outcome found) {
            return new ReplayResult(ReplayResult.Status.EXPECTED_OUTCOME, BUSINESS_OUTCOME, found.code, step.get(), Map.of());
        } catch (Stop stop) {
            return ReplayResult.failure(stop.code, step.get());
        } catch (com.microsoft.playwright.TimeoutError ex) {
            return ReplayResult.failure(guard != null && guard.denied() ? POLICY_DENIED : TIMEOUT, step.get());
        } catch (RuntimeException ex) {
            return ReplayResult.failure(guard != null && guard.denied() ? POLICY_DENIED :
                    System.nanoTime() >= Math.min(deadline, stepDeadline) || Thread.currentThread().isInterrupted()
                            ? TIMEOUT : BROWSER_FAILURE, step.get());
        }
    }

    private String fillValue(StepSpec s) {
        return s.action() == StepSpec.Action.FILL ? ContractValues.text(ContractValues.input(s.inputExpression(), inputs)) : null;
    }

    private void beginStep() {
        stepDeadline = Math.min(deadline, System.nanoTime() + options.stepTimeout().toNanos());
        remaining();
    }

    private double remaining() {
        long nanos = Math.min(deadline, stepDeadline) - System.nanoTime();
        if (nanos <= 0 || Thread.currentThread().isInterrupted()) fail(TIMEOUT);
        double millis = Math.max(1, nanos / 1_000_000.0);
        if (page != null) {
            page.setDefaultTimeout(millis);
            page.setDefaultNavigationTimeout(millis);
        }
        return millis;
    }

    private void authorizedPage() {
        remaining();
        if (guard.denied() || dialog || !policy.allowsUrl(page.url())) fail(POLICY_DENIED);
    }

    private void businessOutcome() {
        authorizedPage();
        String found = null;
        for (OutcomeSpec o : artifact.outcomes()) {
            List<ElementHandle> matches = matches(o.condition().locator());
            try {
                if (matches.size() > 1) fail(AMBIGUOUS_LOCATOR);
                if (matches.size() == 1) {
                    if (found != null) fail(AMBIGUOUS_LOCATOR);
                    found = o.code();
                }
            } finally { dispose(matches); }
        }
        if (found != null) throw new Outcome(found);
    }

    private List<ElementHandle> matches(LocatorSpec spec) {
        remaining();
        String name = ContractValues.resolve(spec.accessibleName(), inputs);
        var opts = new Page.GetByRoleOptions();
        if (spec.nameMatch() == LocatorSpec.NameMatch.EXACT) opts.setName(name).setExact(true);
        else opts.setName(Pattern.compile("^" + regexLiteral(name)));
        Locator locator = page.getByRole(AriaRole.valueOf(spec.role().name()), opts);
        if (locator.count() > 200) fail(AMBIGUOUS_LOCATOR);
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
        verify(new PostconditionSpec(PostconditionSpec.Kind.VISIBLE, artifact.checkpoint().marker(), null), CHECKPOINT_FAILED);
    }

    private Map<String, Object> extract() {
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
    private static void fail(ReplayResult.Code code) { throw new Stop(code); }
    private static final class Stop extends RuntimeException {
        final ReplayResult.Code code;
        Stop(ReplayResult.Code code) { super("REPLAY_STOP", null, false, false); this.code = code; }
    }
    private static final class Outcome extends RuntimeException {
        final String code;
        Outcome(String code) { super("REPLAY_OUTCOME", null, false, false); this.code = code; }
    }
}
