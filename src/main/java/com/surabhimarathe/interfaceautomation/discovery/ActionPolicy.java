package com.surabhimarathe.interfaceautomation.discovery;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Default deny: origin, exact route patterns, action type and control meaning. */
public record ActionPolicy(String origin, List<Pattern> routes, Set<UiAction.Type> actions) {
    public ActionPolicy { routes = List.copyOf(routes); actions = Set.copyOf(actions); }
    public boolean allowsUrl(String url) {
        try {
            URI u = URI.create(url);
            String actualOrigin = u.getScheme() + "://" + u.getRawAuthority();
            return origin.equals(actualOrigin) && u.getUserInfo() == null && u.getRawQuery() == null
                && u.getRawFragment() == null && !u.getRawPath().contains("%")
                && !u.getRawPath().contains("..")
                && routes.stream().anyMatch(p -> p.matcher(u.getRawPath()).matches())
                && !u.getPath().endsWith("/submit");
        } catch (RuntimeException e) { return false; }
    }
    public boolean permits(UiAction action, String currentUrl, String destination, String controlName) {
        if (!actions.contains(action.type()) || !allowsUrl(currentUrl)) return false;
        if (action.type() == UiAction.Type.FILL) return true;
        // Only known, reversible target controls are supported in this initial slice.
        return Set.of("Search", "Open", "Prepare fee reversal", "Review reversal", "New search",
                "Back to member", "Start a new review").contains(controlName)
            && destination != null && allowsUrl(destination);
    }
}
