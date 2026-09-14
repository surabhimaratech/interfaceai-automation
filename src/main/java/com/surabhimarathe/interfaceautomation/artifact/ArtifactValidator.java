package com.surabhimarathe.interfaceautomation.artifact;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.surabhimarathe.interfaceautomation.artifact.ValidationCode.*;

/** Schema 1 is deliberately narrow. Validation never executes a locator or expression. */
public final class ArtifactValidator {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_NAME = 160;
    public static final int MAX_CONTEXT = 300;
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{inputs\\.([A-Za-z][A-Za-z0-9_]*)}");
    public static final int MAX_DISPLAY_NAME = 160;
    public static final int MAX_DESCRIPTION = 1000;
    public static final int MAX_CONTRACT_DESCRIPTION = 500;

    public void validate(CapabilityArtifact a) { validate(a, false); }
    /** Trusted host declarations are validated separately and must contain no executable steps. */
    public void validateDefinition(CapabilityArtifact a) { validate(a, true); }
    private void validate(CapabilityArtifact a, boolean definition) {
        require(a != null, REQUIRED_FIELD, "$");
        require(a.schemaVersion() == SCHEMA_VERSION, UNSUPPORTED_SCHEMA_VERSION, "schemaVersion");
        require(a.artifactVersion() > 0, INVALID_ARTIFACT_VERSION, "artifactVersion");
        identifier(a.capabilityId(), "capabilityId", true);
        metadata(a.displayName(), MAX_DISPLAY_NAME, "displayName");
        metadata(a.description(), MAX_DESCRIPTION, "description");
        require(a.executionBoundary() != null && a.executionBoundary().mode() == ExecutionBoundary.Mode.REVIEW_ONLY,
                INVALID_EXECUTION_BOUNDARY, "executionBoundary");
        target(a.target());
        require(a.inputs() != null && a.inputs().size() <= 32, REQUIRED_FIELD, "inputs");
        require(a.outputs() != null && !a.outputs().isEmpty() && a.outputs().size() <= 32, REQUIRED_FIELD, "outputs");
        // Use indexed paths: user-controlled map keys must not leak through diagnostics.
        int n = 0;
        for (var e : a.inputs().entrySet()) {
            String p = "inputs[" + n++ + "]";
            identifier(e.getKey(), p, false);
            require(e.getValue() != null, REQUIRED_FIELD, p);
            metadata(e.getValue().description(), MAX_CONTRACT_DESCRIPTION, p + ".description");
            constraints(e.getValue().type(), e.getValue().constraints(), p);
        }
        n = 0;
        for (var e : a.outputs().entrySet()) {
            String p = "outputs[" + n++ + "]";
            identifier(e.getKey(), p, false);
            require(e.getValue() != null, REQUIRED_FIELD, p);
            metadata(e.getValue().description(), MAX_CONTRACT_DESCRIPTION, p + ".description");
            constraints(e.getValue().type(), e.getValue().constraints(), p);
        }
        require(a.steps() != null && (definition ? a.steps().isEmpty() : !a.steps().isEmpty()) && a.steps().size() <= 100, REQUIRED_FIELD, "steps");
        var ids = new HashSet<String>();
        for (int i = 0; i < a.steps().size(); i++) {
            StepSpec s = a.steps().get(i);
            String p = "steps[" + i + "]";
            require(s != null, REQUIRED_FIELD, p);
            identifier(s.id(), p + ".id", true);
            require(ids.add(s.id()), DUPLICATE_STEP_ID, p + ".id");
            locator(s.locator(), a.inputs(), p + ".locator");
            require(s.action() != null, UNSAFE_ACTION, p + ".action");
            require(s.locator().nameMatch() == LocatorSpec.NameMatch.EXACT, INVALID_LOCATOR, p + ".locator");
            if (s.action() == StepSpec.Action.FILL) {
                require(s.locator().role() == LocatorSpec.Role.TEXTBOX, UNSAFE_ACTION, p + ".locator");
                expression(s.inputExpression(), a.inputs(), p + ".inputExpression");
                postcondition(s.postcondition(), a.inputs(), p + ".postcondition");
                require(s.postcondition().kind() == PostconditionSpec.Kind.VALUE_EQUALS_INPUT
                        && s.locator().equals(s.postcondition().locator())
                        && s.inputExpression().equals(s.postcondition().inputExpression()), INVALID_POSTCONDITION, p + ".postcondition");
            } else {
                // Structural validation is not authorization: runtime ActionPolicy checks the actual control and destination.
                require(s.locator().role() == LocatorSpec.Role.BUTTON || s.locator().role() == LocatorSpec.Role.LINK,
                        UNSAFE_ACTION, p + ".locator");
                require(s.inputExpression() == null, INVALID_EXPRESSION, p + ".inputExpression");
                postcondition(s.postcondition(), a.inputs(), p + ".postcondition");
                require(s.postcondition().kind() == PostconditionSpec.Kind.VISIBLE, INVALID_POSTCONDITION, p + ".postcondition");
            }
        }
        require(a.outcomes() != null && !a.outcomes().isEmpty() && a.outcomes().size() <= 32, REQUIRED_FIELD, "outcomes");
        var codes = new HashSet<String>();
        for (int i = 0; i < a.outcomes().size(); i++) {
            OutcomeSpec o = a.outcomes().get(i);
            String p = "outcomes[" + i + "]";
            require(o != null, REQUIRED_FIELD, p);
            identifier(o.code(), p + ".code", false);
            require(codes.add(o.code()), DUPLICATE_OUTCOME_CODE, p + ".code");
            postcondition(o.condition(), a.inputs(), p + ".condition");
            require(o.condition().kind() == PostconditionSpec.Kind.VISIBLE
                    && Set.of(LocatorSpec.Role.STATUS, LocatorSpec.Role.ALERT).contains(o.condition().locator().role()),
                    INVALID_POSTCONDITION, p + ".condition");
        }
        CheckpointSpec c = a.checkpoint();
        require(c != null, MISSING_CHECKPOINT, "checkpoint");
        locator(c.marker(), a.inputs(), "checkpoint.marker");
        require(c.marker().role() == LocatorSpec.Role.HEADING && c.marker().nameMatch() == LocatorSpec.NameMatch.EXACT,
                INVALID_LOCATOR, "checkpoint.marker");
        require(c.extractors() != null && c.extractors().size() <= 32, MISSING_OUTPUT_EXTRACTOR, "checkpoint.extractors");
        var extracted = new HashSet<String>();
        for (int i = 0; i < c.extractors().size(); i++) {
            String p = "checkpoint.extractors[" + i + "]";
            ExtractorSpec e = c.extractors().get(i);
            require(e != null, MISSING_OUTPUT_EXTRACTOR, p);
            require(e.output() != null && a.outputs().containsKey(e.output()), MISSING_OUTPUT_REFERENCE, p + ".output");
            require(extracted.add(e.output()), DUPLICATE_OUTPUT_EXTRACTOR, p + ".output");
            locator(e.locator(), a.inputs(), p + ".locator");
            ValueType type = a.outputs().get(e.output()).type();
            require(e.read() != null && e.format() != null, INVALID_EXTRACTOR, p);
            require(e.read() != ExtractorSpec.Read.ROW_VALUE || e.locator().role() == LocatorSpec.Role.ROW, INVALID_EXTRACTOR, p);
            require((e.format() == ExtractorSpec.Format.TEXT) == (type == ValueType.STRING), INVALID_EXTRACTOR, p + ".format");
            if (e.expectedInputExpression() != null) {
                String input = expression(e.expectedInputExpression(), a.inputs(), p + ".expectedInputExpression");
                require(a.inputs().get(input).type() == type, INVALID_EXTRACTOR, p + ".expectedInputExpression");
            }
        }
        require(extracted.equals(a.outputs().keySet()), MISSING_OUTPUT_EXTRACTOR, "checkpoint.extractors");
        require(a.provenance() != null && a.provenance().source() != null, INVALID_PROVENANCE, "provenance");
        require((a.provenance().source() == Provenance.Source.COMPILED_TRACE) == (a.provenance().traceId() != null),
                INVALID_PROVENANCE, "provenance.traceId");
    }

    private void target(TargetSpec t) {
        require(t != null && t.targetId() != null && t.entryPath() != null, INVALID_TARGET, "target");
        require(t.targetId().length() <= 80 && t.targetId().matches("[A-Za-z][A-Za-z0-9_-]*"),
                INVALID_TARGET, "target.targetId");
        require(t.entryPath().length() <= 160 && t.entryPath().matches("/[A-Za-z0-9/_-]*")
                && !t.entryPath().startsWith("//"), INVALID_TARGET, "target.entryPath");
    }

    static void constraints(ValueType type, Constraints c, String p) {
        require(type != null && c != null, INVALID_CONSTRAINTS, p);
        if (type == ValueType.STRING) {
            require(c.minLength() != null && c.maxLength() != null && c.minLength() >= 0
                    && c.maxLength() >= c.minLength() && c.maxLength() <= 1000
                    && c.minimum() == null && c.maximum() == null && c.maxScale() == null, INVALID_CONSTRAINTS, p);
        } else {
            require(c.minLength() == null && c.maxLength() == null && c.minimum() != null && c.maximum() != null
                    && c.minimum().precision() <= 30 && c.maximum().precision() <= 30
                    && Math.abs((long) c.minimum().scale()) <= 8 && Math.abs((long) c.maximum().scale()) <= 8
                    && c.minimum().compareTo(c.maximum()) <= 0 && c.maxScale() != null
                    && c.maxScale() >= 0 && c.maxScale() <= 8, INVALID_CONSTRAINTS, p);
            require(Math.max(0, c.minimum().stripTrailingZeros().scale()) <= c.maxScale()
                    && Math.max(0, c.maximum().stripTrailingZeros().scale()) <= c.maxScale(), INVALID_CONSTRAINTS, p);
        }
    }

    private void postcondition(PostconditionSpec s, Map<String, InputSpec> inputs, String p) {
        require(s != null && s.kind() != null, INVALID_POSTCONDITION, p);
        locator(s.locator(), inputs, p + ".locator");
        if (s.kind() == PostconditionSpec.Kind.VALUE_EQUALS_INPUT) {
            require(s.locator().role() == LocatorSpec.Role.TEXTBOX, INVALID_POSTCONDITION, p);
            expression(s.inputExpression(), inputs, p + ".inputExpression");
        } else require(s.inputExpression() == null, INVALID_EXPRESSION, p + ".inputExpression");
    }

    private void locator(LocatorSpec l, Map<String, InputSpec> inputs, String p) {
        require(l != null && l.role() != null && l.nameMatch() != null
                && l.cardinality() == LocatorSpec.Cardinality.EXACT_ONE, INVALID_LOCATOR, p);
        require(l.nameMatch() != LocatorSpec.NameMatch.PREFIX || l.role() == LocatorSpec.Role.ROW, INVALID_LOCATOR, p + ".nameMatch");
        boundedText(l.accessibleName(), MAX_NAME, inputs, p + ".accessibleName");
        if (l.context() != null) {
            require(l.context().kind() != null, INVALID_LOCATOR, p + ".context");
            boundedText(l.context().text(), MAX_CONTEXT, inputs, p + ".context.text");
        }
    }

    private void boundedText(String text, int max, Map<String, InputSpec> inputs, String p) {
        require(text != null && !text.isBlank() && text.length() <= max
                && text.codePoints().noneMatch(Character::isISOControl), UNBOUNDED_LOCATOR_TEXT, p);
        if (text.contains("$") || text.contains("{") || text.contains("}")) {
            String key = expression(text, inputs, p);
            InputSpec spec = inputs.get(key);
            // Dynamic locator strings must remain bounded after substitution too.
            require(spec.type() == ValueType.STRING && spec.constraints().maxLength() <= max, UNBOUNDED_LOCATOR_TEXT, p);
        }
    }

    static String expression(String text, Map<String, InputSpec> inputs, String p) {
        require(text != null, INVALID_EXPRESSION, p);
        var m = EXPRESSION.matcher(text);
        require(m.matches(), INVALID_EXPRESSION, p);
        String key = m.group(1);
        require(inputs.containsKey(key), MISSING_INPUT_REFERENCE, p);
        return key;
    }

    static boolean isExpression(String text) { return text != null && EXPRESSION.matcher(text).matches(); }

    private void metadata(String text, int max, String p) {
        require(text != null && !text.isBlank() && text.length() <= max
                && text.codePoints().noneMatch(Character::isISOControl), INVALID_METADATA, p);
    }

    private void identifier(String id, String p, boolean hyphen) {
        require(id != null && id.length() <= 80 && id.matches(hyphen ? "[A-Za-z][A-Za-z0-9_-]*" : "[A-Za-z][A-Za-z0-9_]*"), INVALID_IDENTIFIER, p);
    }

    static void require(boolean condition, ValidationCode code, String path) {
        if (!condition) throw ArtifactValidationException.at(code, path);
    }
}
