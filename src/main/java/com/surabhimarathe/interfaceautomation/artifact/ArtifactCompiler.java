package com.surabhimarathe.interfaceautomation.artifact;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.surabhimarathe.interfaceautomation.artifact.ArtifactValidator.require;
import static com.surabhimarathe.interfaceautomation.artifact.ValidationCode.*;

/** In-memory only. No model access, filesystem writes, runner integration, or replay. */
public final class ArtifactCompiler {
    private final ArtifactValidator validator = new ArtifactValidator();

    /**
     * A valid declarative draft supplies contracts, checkpoint and outcomes, with no steps.
     * All declared parameters must be supplied; values are used only for binding and leak checks.
     */
    public CapabilityArtifact compile(CapabilityArtifact draft, DiscoveryTrace trace, Map<String, ?> parameters) {
        require(draft != null && draft.inputs() != null && draft.steps() != null && draft.steps().isEmpty(),
                INVALID_TRACE, "draft");
        require(trace != null && parameters != null && parameters.keySet().equals(draft.inputs().keySet()),
                INVALID_PARAMETER, "parameters");
        int index = 0;
        for (var e : draft.inputs().entrySet()) {
            String p = "parameters[" + index++ + "]";
            InputSpec spec = e.getValue();
            require(spec != null, INVALID_CONSTRAINTS, p);
            ArtifactValidator.constraints(spec.type(), spec.constraints(), p);
            checkParameter(spec, parameters.get(e.getKey()), p);
        }
        List<DiscoveryTrace.Entry> entries = trace.snapshot();
        require(!entries.isEmpty(), INVALID_TRACE, "trace");
        List<StepSpec> steps = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            DiscoveryTrace.Entry e = entries.get(i);
            String expr = null;
            PostconditionSpec post = e.postcondition;
            if (e.action == StepSpec.Action.FILL) {
                List<String> matches = new ArrayList<>();
                for (var parameter : parameters.entrySet()) {
                    if (matches(e.fillValue, parameter.getValue()))
                        matches.add(parameter.getKey());
                }
                require(!matches.isEmpty(), UNBOUND_FILL_VALUE, "trace[" + i + "]");
                require(matches.size() == 1, AMBIGUOUS_INPUT_BINDING, "trace[" + i + "]");
                expr = "${inputs." + matches.getFirst() + "}";
                post = new PostconditionSpec(PostconditionSpec.Kind.VALUE_EQUALS_INPUT, e.locator, expr);
            }
            steps.add(new StepSpec("step-" + (i + 1), e.action, e.locator, expr, post));
        }
        CapabilityArtifact result = new CapabilityArtifact(draft.schemaVersion(), draft.artifactVersion(),
                draft.capabilityId(), draft.displayName(), draft.description(), draft.executionBoundary(), draft.target(), draft.inputs(), draft.outputs(), steps,
                draft.outcomes(), draft.checkpoint(), new Provenance(Provenance.Source.COMPILED_TRACE, trace.id()));
        validator.validate(result);
        // Scan every durable string, including contract keys. No partial substitution or label rewriting.
        List<String> strings = durableStrings(result);
        List<String> sensitive = new ArrayList<>();
        for (Object value : parameters.values()) {
            sensitive.add(value instanceof BigDecimal d ? d.toPlainString() : (String) value);
            if (value instanceof BigDecimal d) sensitive.add(d.stripTrailingZeros().toPlainString());
        }
        for (DiscoveryTrace.Entry e : entries) if (e.fillValue != null) sensitive.add(e.fillValue);
        for (String text : strings) {
            if (text == null || ArtifactValidator.isExpression(text)) continue;
            for (String value : sensitive) {
                require(value.isEmpty() || !text.contains(value), DISCOVERY_VALUE_LEAK, "artifact");
            }
        }
        return result;
    }

    private void checkParameter(InputSpec spec, Object value, String p) {
        Constraints c = spec.constraints();
        if (spec.type() == ValueType.STRING) {
            require(value instanceof String, INVALID_PARAMETER, p);
            String s = (String) value;
            require(s.length() >= c.minLength() && s.length() <= c.maxLength()
                    && s.codePoints().noneMatch(Character::isISOControl), INVALID_PARAMETER, p);
        } else {
            require(value instanceof BigDecimal, INVALID_PARAMETER, p);
            BigDecimal d = (BigDecimal) value;
            require(d.precision() <= 30 && Math.abs((long) d.scale()) <= 8
                    && d.compareTo(c.minimum()) >= 0 && d.compareTo(c.maximum()) <= 0
                    && Math.max(0, d.stripTrailingZeros().scale()) <= c.maxScale(), INVALID_PARAMETER, p);
        }
    }

    private boolean matches(String fill, Object value) {
        if (value instanceof String s) return s.equals(fill);
        // Only plain decimal lexical forms; no trim, exponent, grouping, or currency normalization.
        return value instanceof BigDecimal d && fill != null
                && fill.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")
                && new BigDecimal(fill).compareTo(d) == 0;
    }

    private List<String> durableStrings(CapabilityArtifact a) {
        List<String> text = new ArrayList<>();
        text.add(a.capabilityId());
        text.add(a.displayName());
        text.add(a.description());
        text.add(a.target().targetId());
        a.inputs().values().forEach(s -> text.add(s.description()));
        a.outputs().values().forEach(s -> text.add(s.description()));
        text.add(a.target().entryPath());
        text.addAll(a.inputs().keySet());
        text.addAll(a.outputs().keySet());
        for (StepSpec s : a.steps()) {
            text.add(s.id());
            locatorStrings(text, s.locator());
            text.add(s.inputExpression());
            locatorStrings(text, s.postcondition().locator());
            text.add(s.postcondition().inputExpression());
        }
        for (OutcomeSpec o : a.outcomes()) {
            text.add(o.code());
            locatorStrings(text, o.condition().locator());
            text.add(o.condition().inputExpression());
        }
        locatorStrings(text, a.checkpoint().marker());
        for (ExtractorSpec e : a.checkpoint().extractors()) {
            text.add(e.output());
            locatorStrings(text, e.locator());
            text.add(e.expectedInputExpression());
        }
        return text;
    }

    private void locatorStrings(List<String> text, LocatorSpec locator) {
        text.add(locator.accessibleName());
        if (locator.context() != null) text.add(locator.context().text());
    }
}
