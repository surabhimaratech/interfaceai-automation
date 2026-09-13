package com.surabhimarathe.interfaceautomation.discovery;

import java.util.UUID;

public record UiAction(UUID observationId, Type type, String controlId, String value) {
    public enum Type { FILL, CLICK, COMPLETE, WAIT, REQUEST_HUMAN }
    public UiAction {
        if (observationId == null || type == null || controlId == null)
            throw new IllegalArgumentException("INVALID_ACTION");
        boolean targeted = type == Type.FILL || type == Type.CLICK;
        if (targeted ? !controlId.matches("c[0-9]+") : !controlId.isEmpty())
            throw new IllegalArgumentException("INVALID_TARGET");
        if (type == Type.FILL && (value == null || value.length() > 120))
            throw new IllegalArgumentException("INVALID_FILL");
        if (type != Type.FILL && value != null && !value.isEmpty())
            throw new IllegalArgumentException("CLICK_HAS_VALUE");
    }
}
