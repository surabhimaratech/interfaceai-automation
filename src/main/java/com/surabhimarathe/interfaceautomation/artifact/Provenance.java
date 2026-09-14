package com.surabhimarathe.interfaceautomation.artifact;

import java.util.UUID;

/** Trace identity only; excludes model messages, credentials, timestamps containing user text, and inputs. */
public record Provenance(Source source, UUID traceId) {
    public enum Source { HAND_AUTHORED_EXAMPLE, COMPILED_TRACE }
}
