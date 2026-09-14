package com.surabhimarathe.interfaceautomation.discovery;

import com.microsoft.playwright.*;

/** Shared request interception and last-moment action authorization for discovery and replay. */
public final class BrowserPolicyGuard {
    private final ActionPolicy policy;
    private boolean denied;
    public BrowserPolicyGuard(BrowserContext context, ActionPolicy policy) {
        this.policy = policy;
        context.route("**/*", route -> {
            String url = route.request().url();
            // Preserve the existing synthetic target stylesheet exception; it cannot authorize navigation.
            boolean style = url.equals(policy.origin() + "/legacy.css")
                    && route.request().resourceType().equals("stylesheet");
            if (policy.allowsUrl(url) || style) route.resume();
            else { denied = true; route.abort(); }
        });
    }

    public boolean denied() { return denied; }

    public boolean permits(UiAction action, Page page, ElementHandle target) {
        return permits(action.type(), page, target);
    }
    public boolean permits(UiAction.Type type, Page page, ElementHandle target) {
        String destination = (String) target.evaluate("""
            e => e.tagName === 'A' ? e.href :
                 (e.form && (e.type === 'submit' || e.type === 'image')) ? (e.formAction || e.form.action) : null
            """);
        return policy.permits(type, page.url(), destination, controlName(target));
    }
    public static String controlName(ElementHandle target) {
        return (String) target.evaluate("""
            e => {
                const ids = (e.getAttribute('aria-labelledby') || '').trim().split(/\\s+/).filter(Boolean);
                const referenced = ids.map(id => document.getElementById(id)?.textContent || '').join(' ');
                return (referenced || e.getAttribute('aria-label') ||
                    (e.labels && Array.from(e.labels).map(l => l.innerText).join(' ')) ||
                    e.innerText || (e.type === 'submit' ? e.value : '') || '').replace(/\\s+/g, ' ').trim();
            }
            """);
    }
}
