package com.surabhimarathe.interfaceautomation.artifact;

import java.util.List;

public record CheckpointSpec(LocatorSpec marker, List<ExtractorSpec> extractors) {
    public CheckpointSpec { extractors = extractors == null ? null : List.copyOf(extractors); }
}
