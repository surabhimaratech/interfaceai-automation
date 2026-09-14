package com.surabhimarathe.interfaceautomation.artifact;

import java.util.List;

/** Errors contain schema paths and codes, never rejected input values. */
public final class ArtifactValidationException extends IllegalArgumentException {
    public record Issue(ValidationCode code, String path) {}
    private final List<Issue> issues;
    public ArtifactValidationException(List<Issue> issues) {
        super("ARTIFACT_VALIDATION_FAILED");
        this.issues = List.copyOf(issues);
    }
    public List<Issue> issues() { return issues; }
    public static ArtifactValidationException at(ValidationCode code, String path) {
        return new ArtifactValidationException(List.of(new Issue(code, path)));
    }
}
