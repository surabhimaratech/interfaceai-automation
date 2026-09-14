package com.surabhimarathe.interfaceautomation.artifact;

/** EXACT_ONE is cardinality, distinct from accessible-name text matching. */
public record LocatorSpec(Role role, String accessibleName, NameMatch nameMatch,
                          ContextSpec context, Cardinality cardinality) {
    public enum Role { TEXTBOX, BUTTON, LINK, HEADING, ROW, STATUS, ALERT }
    public enum NameMatch { EXACT, PREFIX }
    public enum Cardinality { EXACT_ONE }
}
