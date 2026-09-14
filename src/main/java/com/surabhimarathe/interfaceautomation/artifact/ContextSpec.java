package com.surabhimarathe.interfaceautomation.artifact;

/** Nearest row/form/fieldset must contain this literal or one whole input expression. */
public record ContextSpec(Kind kind, String text) {
    public enum Kind { ROW, FORM, FIELDSET }
}
