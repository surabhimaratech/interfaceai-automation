package com.surabhimarathe.interfaceautomation.discovery;

import java.util.UUID;

public record UiAction(UUID observationId, Type type, String controlId, String value) {
    public enum Type { FILL, CLICK }
    public UiAction {
        if (observationId == null || type == null || controlId == null || !controlId.matches("c[0-9]+"))
            throw new IllegalArgumentException("INVALID_ACTION");
        if (type == Type.FILL && (value == null || value.length() > 120))
            throw new IllegalArgumentException("INVALID_FILL");
        if (type == Type.CLICK && value != null && !value.isEmpty())
            throw new IllegalArgumentException("CLICK_HAS_VALUE");
    }
}
