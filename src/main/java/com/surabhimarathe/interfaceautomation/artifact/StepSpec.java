package com.surabhimarathe.interfaceautomation.artifact;

public record StepSpec(String id, Action action, LocatorSpec locator,
                       String inputExpression, PostconditionSpec postcondition) {
    public enum Action { FILL, CLICK }
}
