package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.discovery.ActionPolicy;
import java.util.*;

/** Explicit single-tenant compatibility adapter. There is no inferred/default tenant constructor. */
public final class TargetRegistry {
    private final TenantId tenant;
    private final TenantTargetRegistry registry;
    private TargetRegistry(TenantId tenant, Map<String,ActionPolicy> policies, Map<String,SessionExpiryMarker> markers) {
        if (tenant == null || policies == null || markers == null || !policies.keySet().containsAll(markers.keySet()))
            throw new IllegalArgumentException("INVALID_TARGET_CONFIGURATION");
        var targets = new HashMap<String,TenantTargetConfiguration>();
        policies.forEach((id,policy) -> targets.put(id,new TenantTargetConfiguration(policy,markers.get(id),DiagnosticRedactionPolicy.baseline())));
        this.tenant = tenant;
        registry = new TenantTargetRegistry(Map.of(tenant,targets));
    }
    public static TargetRegistry singleTenant(TenantId tenant, Map<String,ActionPolicy> policies) {
        return singleTenant(tenant,policies,Map.of());
    }
    public static TargetRegistry singleTenant(TenantId tenant, Map<String,ActionPolicy> policies, Map<String,SessionExpiryMarker> markers) {
        return new TargetRegistry(tenant,policies,markers);
    }
    TenantId tenant() { return tenant; }
    TenantTargetRegistry registry() { return registry; }
    @Override public String toString() { return "TargetRegistry[singleTenant=REDACTED]"; }
}
