package com.surabhimarathe.interfaceautomation.discovery;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.regex.Pattern;

/** Default deny: origin, exact route patterns, action type and control meaning. */
public record ActionPolicy(String origin, List<Pattern> routes, Set<UiAction.Type> actions,
                           Map<UiAction.Type, Set<String>> controlNames) {
    public ActionPolicy {
        routes = List.copyOf(routes);
        actions = Set.copyOf(actions);
        controlNames = controlNames.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
    }
    public boolean allowsUrl(String url) {
        try {
            URI u = URI.create(url);
            String actualOrigin = u.getScheme() + "://" + u.getRawAuthority();
            return origin.equals(actualOrigin) && u.getUserInfo() == null && u.getRawQuery() == null
                && u.getRawFragment() == null && !u.getRawPath().contains("%")
                && !u.getRawPath().contains("..")
                && routes.stream().anyMatch(p -> p.matcher(u.getRawPath()).matches())
                && !Pattern.compile("(?i)(?:^|/)submit(?:/|;|$)").matcher(u.getPath()).find();
        } catch (RuntimeException e) { return false; }
    }
    public boolean permits(UiAction action, String currentUrl, String destination, String controlName) {
        return action != null && permits(action.type(), currentUrl, destination, controlName);
    }
    public boolean permits(UiAction.Type type, String currentUrl, String destination, String controlName) {
        if (type == null || controlName == null || !actions.contains(type) || !allowsUrl(currentUrl)
                || !controlNames.getOrDefault(type, Set.of()).contains(controlName)) return false;
        if (type == UiAction.Type.FILL) return destination == null || allowsUrl(destination);
        return type == UiAction.Type.CLICK && destination != null && allowsUrl(destination);
    }
}
