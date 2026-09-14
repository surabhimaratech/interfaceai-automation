package com.surabhimarathe.interfaceautomation.artifact;

public record PostconditionSpec(Kind kind, LocatorSpec locator, String inputExpression) {
    public enum Kind { VISIBLE, VALUE_EQUALS_INPUT }
}
