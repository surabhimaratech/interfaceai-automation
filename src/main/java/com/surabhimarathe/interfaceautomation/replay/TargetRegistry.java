package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.discovery.ActionPolicy;
import java.net.URI;
import java.util.Map;

/** Trusted host configuration only. Artifacts can select an ID but cannot construct an origin or policy. */
public final class TargetRegistry {
    private final Map<String, ActionPolicy> targets;
    public TargetRegistry(Map<String, ActionPolicy> targets) {
        this.targets = Map.copyOf(targets);
        for (var e : this.targets.entrySet()) {
            URI origin;
            try { origin = URI.create(e.getValue().origin()); }
            catch (RuntimeException ex) { throw new IllegalArgumentException("INVALID_TARGET_CONFIGURATION"); }
            if (!e.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,79}")
                    || !("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                    || origin.getHost() == null || origin.getRawUserInfo() != null
                    || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || !origin.getRawPath().isEmpty())
                throw new IllegalArgumentException("INVALID_TARGET_CONFIGURATION");
        }
    }
    public ActionPolicy resolve(String targetId) { return targets.get(targetId); }
}
