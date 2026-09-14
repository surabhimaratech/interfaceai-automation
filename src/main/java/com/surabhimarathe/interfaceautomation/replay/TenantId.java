package com.surabhimarathe.interfaceautomation.replay;

/** Caller-supplied execution scope, never an artifact input or printable diagnostic. */
public record TenantId(String value) {
    public TenantId {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("INVALID_TENANT_ID");
    }
    @Override public String toString() { return "TenantId[REDACTED]"; }
}
