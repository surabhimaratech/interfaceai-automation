package com.surabhimarathe.interfaceautomation.artifact;

import java.util.List;
import java.util.Map;

public record CapabilityArtifact(int schemaVersion, int artifactVersion, String capabilityId,
        String displayName, String description, ExecutionBoundary executionBoundary,
        TargetSpec target, Map<String, InputSpec> inputs, Map<String, OutputSpec> outputs,
        List<StepSpec> steps, List<OutcomeSpec> outcomes, CheckpointSpec checkpoint, Provenance provenance) {
    public CapabilityArtifact {
        inputs = inputs == null ? null : Map.copyOf(inputs);
        outputs = outputs == null ? null : Map.copyOf(outputs);
        steps = steps == null ? null : List.copyOf(steps);
        outcomes = outcomes == null ? null : List.copyOf(outcomes);
    }
}
