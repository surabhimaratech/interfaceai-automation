package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.discovery.ActionPolicy;
import java.net.URI;

/** Complete trusted configuration for one tenant/target surface. No fields come from the artifact. */
public record TenantTargetConfiguration(ActionPolicy policy, SessionExpiryMarker expiryMarker,
                                        DiagnosticRedactionPolicy redactionPolicy) {
    public TenantTargetConfiguration {
        if (policy == null || redactionPolicy == null) throw new IllegalArgumentException("INVALID_TARGET_CONFIGURATION");
        URI origin;
        try { origin = URI.create(policy.origin()); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("INVALID_TARGET_CONFIGURATION"); }
        if (!("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
                || origin.getHost() == null || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                || origin.getRawFragment() != null || !origin.getRawPath().isEmpty())
            throw new IllegalArgumentException("INVALID_TARGET_CONFIGURATION");
    }
    @Override public String toString() { return "TenantTargetConfiguration[REDACTED]"; }
}
