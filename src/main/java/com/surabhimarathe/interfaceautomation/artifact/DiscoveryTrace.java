package com.surabhimarathe.interfaceautomation.artifact;

import com.surabhimarathe.interfaceautomation.discovery.ActionResult;
import com.surabhimarathe.interfaceautomation.discovery.UiAction;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;
import static com.surabhimarathe.interfaceautomation.artifact.ValidationCode.INVALID_TRACE;

/**
 * Ephemeral compiler input, not a persistence DTO. The caller supplies semantic descriptors
 * from the fresh observation associated with the executed action. No control IDs or handles
 * are retained. This seam does not change the discovery runner or claim trace authenticity.
 */
@JsonSerialize(using = DiscoveryTrace.NoPersistence.class)
public final class DiscoveryTrace {
    /** Refuse accidental root or nested Jackson serialization, even with field auto-detection. */
    public static final class NoPersistence extends ValueSerializer<Object> {
        @Override public void serialize(Object value, JsonGenerator generator, SerializationContext context) {
            throw ArtifactValidationException.at(INVALID_TRACE, "trace.persistence");
        }
    }

    @JsonSerialize(using = NoPersistence.class)
    static final class Entry {
        final StepSpec.Action action;
        final LocatorSpec locator;
        final String fillValue;
        final PostconditionSpec postcondition;
        Entry(StepSpec.Action action, LocatorSpec locator, String fillValue, PostconditionSpec postcondition) {
            this.action = action;
            this.locator = locator;
            this.fillValue = fillValue;
            this.postcondition = postcondition;
        }
        @Override public String toString() { return "DiscoveryTrace.Entry[redacted]"; }
    }

    private final UUID id = UUID.randomUUID();
    private final List<Entry> entries = new ArrayList<>();

    public synchronized void recordSuccessful(UiAction action, ActionResult result,
                                              LocatorSpec semanticLocator, PostconditionSpec clickPostcondition) {
        ArtifactValidator.require(action != null && result != null && semanticLocator != null
                && result.status() == ActionResult.Status.SUCCEEDED && entries.size() < 100,
                INVALID_TRACE, "trace");
        boolean fill = action.type() == UiAction.Type.FILL;
        boolean click = action.type() == UiAction.Type.CLICK;
        ArtifactValidator.require(fill && result.code() == ActionResult.Code.VALUE_VERIFIED
                || click && result.code() == ActionResult.Code.CLICK_COMPLETED, INVALID_TRACE, "trace");
        ArtifactValidator.require(fill ? clickPostcondition == null : clickPostcondition != null,
                INVALID_TRACE, "trace.postcondition");
        entries.add(new Entry(fill ? StepSpec.Action.FILL : StepSpec.Action.CLICK,
                semanticLocator, fill ? action.value() : null, clickPostcondition));
    }

    synchronized List<Entry> snapshot() { return List.copyOf(entries); }
    UUID id() { return id; }
    @Override public String toString() { return "DiscoveryTrace[redacted]"; }
}
