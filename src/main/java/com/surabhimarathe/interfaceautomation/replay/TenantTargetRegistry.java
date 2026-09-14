package com.surabhimarathe.interfaceautomation.replay;

import java.util.*;

/** Exact composite lookup only. An empty tenant map is valid; a target in another tenant is never a fallback. */
public final class TenantTargetRegistry {
    private final Map<TenantId,Map<String,TenantTargetConfiguration>> tenants;
    public TenantTargetRegistry(Map<TenantId,Map<String,TenantTargetConfiguration>> tenants) {
        if (tenants == null) throw new IllegalArgumentException("INVALID_TENANT_CONFIGURATION");
        var copy = new HashMap<TenantId,Map<String,TenantTargetConfiguration>>();
        for (var tenant : tenants.entrySet()) {
            if (tenant.getKey() == null || tenant.getValue() == null) throw new IllegalArgumentException("INVALID_TENANT_CONFIGURATION");
            for (var target : tenant.getValue().entrySet())
                if (target.getKey() == null || !target.getKey().matches("[A-Za-z][A-Za-z0-9_-]{0,79}") || target.getValue() == null)
                    throw new IllegalArgumentException("INVALID_TARGET_CONFIGURATION");
            copy.put(tenant.getKey(),Map.copyOf(tenant.getValue()));
        }
        this.tenants = Map.copyOf(copy);
    }
    public boolean containsTenant(TenantId tenant) { return tenant != null && tenants.containsKey(tenant); }
    public TenantTargetConfiguration resolve(TenantId tenant, String targetId) {
        var targets = tenant == null ? null : tenants.get(tenant);
        return targets == null ? null : targets.get(targetId);
    }
    List<String> diagnosticSecrets() { return tenants.keySet().stream().map(TenantId::value).toList(); }
    @Override public String toString() { return "TenantTargetRegistry[REDACTED]"; }
}
