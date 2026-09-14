package com.surabhimarathe.interfaceautomation.artifact;

/** Declares intent, never grants permissions or overrides runtime policy. */
public record ExecutionBoundary(Mode mode) {
    public enum Mode { REVIEW_ONLY }
}
