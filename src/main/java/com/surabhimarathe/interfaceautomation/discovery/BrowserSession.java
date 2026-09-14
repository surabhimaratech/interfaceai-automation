package com.surabhimarathe.interfaceautomation.discovery;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
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
        call(() -> { requireAutomation(); latest = null; handoff.request(reason, step); return null; });
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
        call(() -> { requireAutomation(); page.waitForTimeout(Math.min(250, operationMillis)); return null; });
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
            page.navigate(url);
            return observeInternal();
        });
    }
    public Observation observe() { return call(this::observeInternal); }
    public ActionResult execute(UiAction action) {
        return call(() -> {
            requireAutomation();
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
                if (action.type() == UiAction.Type.FILL) {
                    if (!"textbox".equals(control.kind()) || !target.isEditable())
                        return result(ActionResult.Status.BLOCKED, ActionResult.Code.POLICY_DENIED);
                    target.fill(action.value());
                    if (!action.value().equals(target.inputValue()))
                        return result(ActionResult.Status.FAILED, ActionResult.Code.BROWSER_FAILURE);
                } else {
                    if ("textbox".equals(control.kind()))
                        return result(ActionResult.Status.BLOCKED, ActionResult.Code.POLICY_DENIED);
                    target.click();
                }
                if (unexpectedDialog || !policy.allowsUrl(page.url()))
                    return result(ActionResult.Status.BLOCKED, ActionResult.Code.POLICY_DENIED);
                Observation after = observeInternal();
                return new ActionResult(ActionResult.Status.SUCCEEDED,
                    action.type() == UiAction.Type.FILL ? ActionResult.Code.VALUE_VERIFIED : ActionResult.Code.CLICK_COMPLETED, after);
            } catch (RuntimeException e) {
                latest = null;
                return new ActionResult(ActionResult.Status.FAILED, ActionResult.Code.BROWSER_FAILURE, null);
            }
        });
    }
    private ActionResult result(ActionResult.Status status, ActionResult.Code code) {
        latest = null; // A rejected action must be followed by a fresh observation.
        return new ActionResult(status, code, null);
    }
    private Observation observeInternal() {
        requireAutomation();
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
        catch (ExecutionException e) { throw new IllegalStateException("BROWSER_OPERATION_FAILED"); }
    }
    @Override public void close() {
        operationMillis = 5000; // Cleanup has its own bound after the decision deadline.
        try { call(() -> { if (playwright != null) playwright.close(); return null; }); }
        finally { owner.shutdown(); }
    }
}
