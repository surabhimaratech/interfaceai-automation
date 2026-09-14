package com.surabhimarathe.interfaceautomation.discovery;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.surabhimarathe.interfaceautomation.artifact.LocatorSpec;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Every Playwright call, including creation/disposal, runs on the same owner thread. */
public final class BrowserSession implements AutoCloseable, DiscoverySurface {
    private final ExecutorService owner = Executors.newSingleThreadExecutor(r -> new Thread(r, "browser-owner"));
    private final ActionPolicy policy;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private BrowserPolicyGuard policyGuard;
    private Observation latest;
    private final Map<String, ElementHandle> handles = new HashMap<>();
    private boolean unexpectedDialog;
    private volatile long operationMillis = 5000;
    private HandoffCoordinator handoff;
    private ExecutedTrace executedTrace;
    private String traceGate;
    private boolean traceAutomation;

    /** Opt-in before navigation. All declarations/paths/parameters are supplied by trusted host code. */
    public void enableCompilation(TrustedCapabilityDefinition definition, Map<String, Object> parameters,
                                  UUID runId, Path trustedOutputDirectory) {
        call(() -> {
            if (executedTrace != null || !"about:blank".equals(page.url())) throw new IllegalStateException("CAPTURE_MUST_START_BEFORE_RUN");
            executedTrace = new ExecutedTrace(definition, parameters, policy, runId, trustedOutputDirectory);
            traceGate = "__trace_" + runId.toString().replace("-", "");
            context.exposeBinding("__executionTraceInput", (source, args) -> {
                if (args.length == 1 && Boolean.TRUE.equals(args[0])) executedTrace.manual();
                return null;
            });
            String script = """
                (() => {
                    let target = null;
                    let pending = [];
                    let allowance = {};
                    window['%s'] = {
                        begin: (element, kind) => { target = element; allowance = kind === "FILL" ? {input: 1} : {pointerdown: 1, click: 1}; },
                        end: async () => { target = null; await Promise.all(pending); pending = []; }
                    };
                    for (const kind of ['input', 'click', 'pointerdown', 'keydown']) {
                        document.addEventListener(kind, event => {
                            if (!event.isTrusted) return;
                            const expected = target && (event.target === target || target.contains(event.target))
                                && (allowance[event.type] || 0) > 0;
                            if (expected) allowance[event.type]--;
                            else pending.push(window.__executionTraceInput(true));
                        }, true);
                    }
                })();
                """.formatted(traceGate);
            context.addInitScript(script);
            page.evaluate(script);
            page.onFrameNavigated(frame -> {
                if (frame == page.mainFrame() && !traceAutomation) executedTrace.manual();
            });
            return null;
        });
    }
    @Override public void bindRun(UUID runId, boolean correlatedFilename) {
        call(() -> { if (executedTrace != null) executedTrace.bind(runId, correlatedFilename); return null; });
    }
    @Override public void discoveryFinished(DiscoveryRunner.Result result) {
        if (executedTrace == null) return;
        try { call(() -> { endTraceAction(); requirePolicy(); return null; }); }
        catch (PolicyDeniedException ex) { executedTrace.invalidate(); }
        catch (RuntimeException ex) { executedTrace.manual(); }
        // Compilation/persistence has no UI work and must not turn a finished discovery into a browser timeout.
        executedTrace.finish(result);
    }
    public CompilationResult compilationResult() {
        return call(() -> executedTrace == null ? null : executedTrace.result());
    }
    private void beginTraceAction(ElementHandle target, UiAction.Type type) {
        if (executedTrace == null) return;
        traceAutomation = true;
        target.evaluate("(e, args) => window[args.key].begin(e, args.kind)", Map.of("key", traceGate, "kind", type.name()));
    }
    private void endTraceAction() {
        if (executedTrace == null) return;
        try { page.evaluate("key => window[key] ? window[key].end() : null", traceGate); }
        catch (RuntimeException ex) { executedTrace.invalidate(); }
        finally { traceAutomation = false; }
    }

    @Override public void attachHandoff(HandoffCoordinator coordinator) {
        call(() -> {
            handoff = coordinator;
            context.exposeBinding("__manualInput", (source, args) -> { handoff.interaction(); return null; });
            String script = """
                (() => {
                  if (window.__manualCapture) return;
                  window.__manualCapture = true;
                  for (const kind of ['click', 'input']) document.addEventListener(kind, event => {
                    if (event.isTrusted) window.__manualInput().catch(() => {});
                  }, true);
                })();
                """;
            context.addInitScript(script);
            page.evaluate(script);
            return null;
        });
    }
    @Override public void simulateExpiry() {
        call(() -> {
            requireAutomation();
            if (executedTrace != null) executedTrace.manual();
            page.evaluate("""
                () => {
                  const overlay = document.createElement('section');
                  overlay.setAttribute('role', 'alert');
                  overlay.style.cssText = 'position:fixed;inset:0;z-index:9999;background:#fff;padding:60px';
                  const title = document.createElement('h2'); title.textContent = 'Simulated session expired';
                  const text = document.createElement('p'); text.textContent = 'SESSION_EXPIRED — Restore this synthetic session to continue.';
                  const button = document.createElement('button'); button.textContent = 'Restore session';
                  button.onclick = () => overlay.remove();
                  overlay.append(title,text,button); document.body.append(overlay);
                }
                """);
            latest = null;
            return null;
        });
    }
    @Override public void giveToHuman(HandoffCoordinator.Reason reason, int step) {
        call(() -> { requireAutomation(); if (executedTrace != null) executedTrace.manual(); latest = null; handoff.request(reason, step); return null; });
    }
    @Override public void pumpHumanEvents() {
        // Dispatch browser input callbacks without navigating, reading fields or issuing actions.
        call(() -> { page.waitForTimeout(50); return null; });
    }
    @Override public void reclaimFromHuman() {
        call(() -> { latest = null; unexpectedDialog = false; handoff.reclaim(); return null; });
    }
    private void requireAutomation() { if (handoff != null) handoff.requireAutomation(); }

    @Override public void budget(java.time.Duration remaining) {
        operationMillis = Math.max(1, Math.min(5000, remaining.toMillis()));
    }
    @Override public void waitBriefly() {
        call(() -> { requireAutomation(); if (executedTrace != null) executedTrace.nonReplayable();
            requirePolicy(); page.waitForTimeout(Math.min(250, operationMillis)); requirePolicy(); return null; });
    }

    public BrowserSession(ActionPolicy policy) { this(policy, false); }
    BrowserSession(ActionPolicy policy, boolean headless) {
        this(policy, headless, List.of());
    }
    // Local CDP port is used only by integration tests to drive the existing page as a test operator.
    BrowserSession(ActionPolicy policy, boolean headless, List<String> launchArgs) {
        this.policy = policy;
        try { call(() -> {
            try {
                playwright = Playwright.create();
                browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless).setArgs(launchArgs));
                context = browser.newContext(new Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK));
                policyGuard = new BrowserPolicyGuard(context, policy);
                page = context.newPage();
                page.setDefaultTimeout(5000);
                page.setDefaultNavigationTimeout(5000);
                page.onDialog(dialog -> { unexpectedDialog = true; dialog.dismiss(); });
                context.onPage(p -> { if (p != page) p.close(); });
            } catch (RuntimeException e) {
                if (playwright != null) playwright.close();
                throw e;
            }
            return null;
        }); } catch (RuntimeException e) { owner.shutdown(); throw e; }
    }

    public Observation open(String url) {
        return call(() -> {
            requireAutomation();
            if (!policy.allowsUrl(url)) throw new IllegalArgumentException("ENTRY_POLICY_DENIED");
            traceAutomation = true;
            try { requirePolicy(); page.navigate(url); return observeInternal(); }
            catch (RuntimeException ex) { requirePolicy(); throw ex; }
            finally { traceAutomation = false; }
        });
    }
    public Observation observe() { return call(() -> {
        try { return observeInternal(); }
        catch (RuntimeException ex) { requirePolicy(); throw ex; }
    }); }
    public ActionResult execute(UiAction action) {
        return call(() -> {
            requireAutomation();
            if (policyGuard.denied()) return result(ActionResult.Status.BLOCKED, ActionResult.Code.POLICY_DENIED);
            if (action.type() != UiAction.Type.FILL && action.type() != UiAction.Type.CLICK)
                return result(ActionResult.Status.BLOCKED, ActionResult.Code.POLICY_DENIED);
            if (latest == null || !latest.id().equals(action.observationId()))
                return result(ActionResult.Status.BLOCKED, ActionResult.Code.STALE_OBSERVATION);
            ElementHandle target = handles.get(action.controlId());
            if (target == null) return result(ActionResult.Status.BLOCKED, ActionResult.Code.TARGET_CHANGED);
            try {
                var control = latest.controls().stream().filter(c -> c.id().equals(action.controlId())).findFirst().orElseThrow();
                if (!page.url().equals(latest.url()) || !target.isVisible() || !target.isEnabled()
                    || !control.name().equals(name(target)) || !control.context().equals(nearby(target)))
                    return result(ActionResult.Status.BLOCKED, ActionResult.Code.TARGET_CHANGED);
                if (unexpectedDialog || !policyGuard.permits(action, page, target))
                    return result(ActionResult.Status.BLOCKED, ActionResult.Code.POLICY_DENIED);
                LocatorSpec durable = executedTrace == null ? null : executedTrace.before(page, target, action);
                beginTraceAction(target, action.type());
                requirePolicy();
                if (action.type() == UiAction.Type.FILL) {
                    if (!"textbox".equals(control.kind()) || !target.isEditable())
                        return result(ActionResult.Status.BLOCKED, ActionResult.Code.POLICY_DENIED);
                    requirePolicy();
                    target.fill(action.value());
                    requirePolicy();
                    if (!action.value().equals(target.inputValue()))
                        return result(ActionResult.Status.FAILED, ActionResult.Code.BROWSER_FAILURE);
                } else {
                    if ("textbox".equals(control.kind()))
                        return result(ActionResult.Status.BLOCKED, ActionResult.Code.POLICY_DENIED);
                    target.click();
                }
                if (policyGuard.denied() || unexpectedDialog || !policy.allowsUrl(page.url()))
                    return result(ActionResult.Status.BLOCKED, ActionResult.Code.POLICY_DENIED);
                Observation after = observeInternal();
                var post = executedTrace == null ? null : executedTrace.after(page, action);
                endTraceAction();
                requirePolicy();
                ActionResult succeeded = new ActionResult(ActionResult.Status.SUCCEEDED,
                    action.type() == UiAction.Type.FILL ? ActionResult.Code.VALUE_VERIFIED : ActionResult.Code.CLICK_COMPLETED, after);
                if (executedTrace != null) executedTrace.succeeded(action, succeeded, durable, post);
                return succeeded;
            } catch (RuntimeException e) {
                latest = null;
                if (policyGuard.denied() || e instanceof PolicyDeniedException)
                    return result(ActionResult.Status.BLOCKED, ActionResult.Code.POLICY_DENIED);
                return new ActionResult(ActionResult.Status.FAILED, ActionResult.Code.BROWSER_FAILURE, null);
            } finally { if (traceAutomation) endTraceAction(); }
        });
    }
    private ActionResult result(ActionResult.Status status, ActionResult.Code code) {
        latest = null; // A rejected action must be followed by a fresh observation.
        if (policyGuard.denied()) {
            if (executedTrace != null) executedTrace.invalidate();
            return new ActionResult(ActionResult.Status.BLOCKED, ActionResult.Code.POLICY_DENIED, null);
        }
        return new ActionResult(status, code, null);
    }
    private void requirePolicy() {
        if (policyGuard.denied()) {
            latest = null;
            if (executedTrace != null) executedTrace.invalidate();
            throw new PolicyDeniedException();
        }
    }
    private Observation observeInternal() {
        requireAutomation();
        requirePolicy();
        if (!policy.allowsUrl(page.url())) throw new IllegalStateException("OBSERVATION_POLICY_DENIED");
        for (var h : handles.values()) h.dispose();
        handles.clear();
        List<Observation.Control> controls = new ArrayList<>();
        for (ElementHandle h : page.querySelectorAll("input:not([type=password]):not([type=hidden]), textarea, button, a[href]")) {
            if (controls.size() >= 40) { h.dispose(); continue; }
            if (!h.isVisible() || !h.isEnabled()) { h.dispose(); continue; }
            String kind = (String) h.evaluate("e => e.tagName === 'A' ? 'link' : (e.tagName === 'BUTTON' || e.type === 'submit') ? 'button' : 'textbox'");
            String id = "c" + controls.size();
            controls.add(new Observation.Control(id, kind, name(h), nearby(h),
                "textbox".equals(kind) && !h.inputValue().isEmpty()));
            handles.put(id, h);
        }
        Map<String,String> details = new LinkedHashMap<>();
        if (page.url().endsWith("/fee-reversal/review")) {
            for (String label : List.of("Member ID", "Name", "Account", "Current balance", "Reversal amount", "Reason", "Projected balance")) {
                // Resolve the unique visible header/value pair; never use application internals.
                var headers = page.locator("main th").filter(new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("^" + label + "$")));
                if (headers.count() == 1 && headers.first().isVisible()) {
                    var cell = headers.first().locator("xpath=following-sibling::td");
                    if (cell.count() == 1 && cell.isVisible()) details.put(label, ContextText.clean(cell.innerText(), 160));
                }
            }
        }
        latest = new Observation(UUID.randomUUID(), page.url(),
            page.locator("h1").count() == 0 ? "" : page.locator("h1").first().innerText(), controls,
            messages("[role=status]"), messages("[role=alert]"), details);
        requirePolicy();
        return latest;
    }
    private List<String> messages(String selector) {
        var result = new ArrayList<String>();
        var nodes = page.locator(selector);
        for (int i = 0; i < Math.min(nodes.count(), 5); i++)
            if (nodes.nth(i).isVisible()) result.add(ContextText.clean(nodes.nth(i).innerText(), 300));
        if (unexpectedDialog) result.add("UNEXPECTED_DIALOG");
        return result;
    }
    private String nearby(ElementHandle h) {
        return ContextText.clean((String) h.evaluate("""
            e => ['tr', 'fieldset', 'form'].map(selector => {
                const parent = e.closest(selector);
                return parent ? parent.innerText.slice(0, 2000) : '';
            }).filter(Boolean).join(' | ')
            """), 300);
    }
    private String name(ElementHandle h) {
        return ContextText.clean((String) h.evaluate("""
            e => (e.getAttribute('aria-label') || (e.labels && Array.from(e.labels).map(l => l.innerText).join(' ')) ||
                  e.innerText || (e.type === 'submit' ? e.value : '') || '').trim().slice(0, 160)
            """), 160);
    }
    private <T> T call(Callable<T> work) {
        Future<T> pending = owner.submit(() -> {
            if (page != null) {
                page.setDefaultTimeout(operationMillis);
                page.setDefaultNavigationTimeout(operationMillis);
            }
            return work.call();
        });
        try { return pending.get(operationMillis + (page == null ? 15000 : 0), TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { pending.cancel(true); throw new IllegalStateException("BROWSER_TIMEOUT"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("BROWSER_INTERRUPTED"); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof PolicyDeniedException denied) throw denied;
            throw new IllegalStateException("BROWSER_OPERATION_FAILED");
        }
    }
    @Override public void close() {
        operationMillis = 5000; // Cleanup has its own bound after the decision deadline.
        try { call(() -> { if (playwright != null) playwright.close(); return null; }); }
        finally { owner.shutdown(); }
    }
}
