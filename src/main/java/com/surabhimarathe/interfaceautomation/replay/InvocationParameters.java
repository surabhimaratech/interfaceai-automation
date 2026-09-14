package com.surabhimarathe.interfaceautomation.replay;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only String and BigDecimal values are accepted, then checked against the artifact contracts. */
public record InvocationParameters(Map<String, Object> values) {
    public InvocationParameters {
        values = values == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
    @Override public String toString() { return "InvocationParameters[REDACTED]"; }
}
