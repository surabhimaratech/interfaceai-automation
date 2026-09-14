package com.surabhimarathe.interfaceautomation.discovery;

import com.surabhimarathe.interfaceautomation.artifact.*;
import java.util.Set;

/** Host-authored declarations and a privacy vocabulary, not a model plan or recorded step sequence. */
public record TrustedCapabilityDefinition(CapabilityArtifact draft, Set<String> headings, Set<String> rowAnchors) {
    public TrustedCapabilityDefinition {
        new ArtifactValidator().validateDefinition(draft);
        headings = Set.copyOf(headings);
        rowAnchors = Set.copyOf(rowAnchors);
        if (headings.isEmpty() || headings.size() > 32 || rowAnchors.size() > 32)
            throw new IllegalArgumentException("INVALID_CAPTURE_VOCABULARY");
        for (String text : java.util.stream.Stream.concat(headings.stream(), rowAnchors.stream()).toList())
            if (text.isBlank() || text.length() > 160 || text.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("INVALID_CAPTURE_VOCABULARY");
    }
}
