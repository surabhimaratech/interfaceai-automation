package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.artifact.LocatorSpec;

/** Exact semantic marker from trusted target configuration, never from artifact/model/page prose. */
public record SessionExpiryMarker(LocatorSpec.Role role, String name) {
    public SessionExpiryMarker {
        if ((role != LocatorSpec.Role.ALERT && role != LocatorSpec.Role.STATUS) || name == null
                || name.isBlank() || name.length() > 160 || name.contains("$")
                || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("INVALID_SESSION_MARKER");
    }
    @Override public String toString() { return "SessionExpiryMarker[role=" + role + ", name=REDACTED]"; }
}
