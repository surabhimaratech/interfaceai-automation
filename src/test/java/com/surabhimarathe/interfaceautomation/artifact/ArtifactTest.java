package com.surabhimarathe.interfaceautomation.artifact;

import com.surabhimarathe.interfaceautomation.discovery.ActionResult;
import com.surabhimarathe.interfaceautomation.discovery.ActionPolicy;
import java.util.Set;
import java.util.regex.Pattern;
import com.surabhimarathe.interfaceautomation.discovery.UiAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static com.surabhimarathe.interfaceautomation.artifact.ValidationCode.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactTest {
    private final ArtifactJson json = new ArtifactJson();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArtifactCompiler compiler = new ArtifactCompiler();

    private String exampleText() throws IOException {
        try (var stream = getClass().getResourceAsStream("/artifacts/prepare-fee-reversal-review.v1.json")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private CapabilityArtifact example() throws IOException { return json.read(exampleText()); }

    private void code(ValidationCode expected, Runnable task) {
        ArtifactValidationException e = assertThrows(ArtifactValidationException.class, task::run);
        assertEquals(expected, e.issues().getFirst().code());
        assertEquals("ARTIFACT_VALIDATION_FAILED", e.getMessage());
        assertNull(e.getCause());
    }

    private void malformed(ValidationCode expected, Consumer<ObjectNode> mutation) throws IOException {
        ObjectNode tree = (ObjectNode) mapper.readTree(exampleText());
        mutation.accept(tree);
        code(expected, () -> json.read(tree.toString()));
    }

    private ObjectNode at(ObjectNode node, String pointer) { return (ObjectNode) node.at(pointer); }

    private Map<String, Object> parameters() {
        return Map.of("memberId", "100042", "amount", new BigDecimal("25"), "reason", "Courtesy adjustment");
    }

    private CapabilityArtifact draft(CapabilityArtifact a, Map<String, InputSpec> inputs) {
        return new CapabilityArtifact(a.schemaVersion(), a.artifactVersion(), a.capabilityId(), a.displayName(), a.description(), a.executionBoundary(), a.target(),
                inputs, a.outputs(), List.of(), a.outcomes(), a.checkpoint(), a.provenance());
    }

    private DiscoveryTrace trace(CapabilityArtifact a) {
        DiscoveryTrace t = new DiscoveryTrace();
        for (StepSpec step : a.steps()) {
            String value = switch (step.locator().accessibleName()) {
                case "Member ID" -> "100042";
                case "Amount" -> "25.00";
                case "Reason" -> "Courtesy adjustment";
                default -> null;
            };
            add(t, step, value);
        }
        return t;
    }

    private void add(DiscoveryTrace trace, StepSpec s, String value) {
        boolean fill = s.action() == StepSpec.Action.FILL;
        trace.recordSuccessful(new UiAction(UUID.randomUUID(), fill ? UiAction.Type.FILL : UiAction.Type.CLICK, "c9876", value),
                new ActionResult(ActionResult.Status.SUCCEEDED,
                        fill ? ActionResult.Code.VALUE_VERIFIED : ActionResult.Code.CLICK_COMPLETED, null),
                s.locator(), fill ? null : s.postcondition());
    }

    @Test void exampleValidatesAndRoundTripsWithoutRuntimeMaterial() throws IOException {
        CapabilityArtifact a = example();
        new ArtifactValidator().validate(a);
        assertEquals(a, json.read(json.write(a)));
        assertEquals(1, a.schemaVersion());
        assertEquals(1, a.artifactVersion());
        assertEquals(8, a.steps().size());
        assertEquals(7, a.checkpoint().extractors().size());
        assertEquals(Provenance.Source.HAND_AUTHORED_EXAMPLE, a.provenance().source());
        String text = json.write(a);
        for (String forbidden : List.of("controlId", "observationId", "css", "xpath", "transcript", "100042"))
            assertFalse(text.contains(forbidden));
        assertEquals("${inputs.memberId}", a.steps().get(2).locator().context().text());
        assertEquals("Savings", a.steps().get(3).locator().context().text());
    }

    @Test void compilerParameterizesSuccessfulActionsWithoutPersistingValuesOrIds() throws IOException {
        CapabilityArtifact a = example();
        DiscoveryTrace trace = trace(a);
        CapabilityArtifact result = compiler.compile(draft(a, a.inputs()), trace, parameters());
        assertEquals(a.steps(), result.steps());
        assertEquals(a.displayName(), result.displayName());
        assertEquals(a.description(), result.description());
        assertEquals(a.executionBoundary(), result.executionBoundary());
        assertEquals(a.target(), result.target());
        assertEquals(a.inputs(), result.inputs());
        assertEquals(a.outputs(), result.outputs());
        assertEquals(a.outcomes(), result.outcomes());
        assertEquals(Provenance.Source.COMPILED_TRACE, result.provenance().source());
        assertNotNull(result.provenance().traceId());
        assertEquals(result, json.read(json.write(result)));
        String text = json.write(result);
        for (String secret : List.of("100042", "25.00", "Courtesy adjustment", "c9876", "observationId", "fillValue"))
            assertFalse(text.contains(secret));
        assertEquals("DiscoveryTrace[redacted]", trace.toString());
        assertFalse(trace.snapshot().getFirst().toString().contains("100042"));
    }

    @ParameterizedTest @ValueSource(strings = {"unknown-secret", " 100042", "100042 ", "2.5e1", "$25.00", "25,00"})
    void unknownLiteralAndNonCanonicalFillAreRejected(String literal) throws IOException {
        CapabilityArtifact a = example();
        DiscoveryTrace t = new DiscoveryTrace();
        add(t, a.steps().getFirst(), literal);
        code(UNBOUND_FILL_VALUE, () -> compiler.compile(draft(a, a.inputs()), t, parameters()));
    }

    @Test void ambiguousBindingsFailRatherThanPickingAnInput() throws IOException {
        CapabilityArtifact a = example();
        var inputs = new LinkedHashMap<>(a.inputs());
        inputs.put("anotherMember", inputs.get("memberId"));
        var parameters = new LinkedHashMap<>(parameters());
        parameters.put("anotherMember", "100042");
        code(AMBIGUOUS_INPUT_BINDING, () -> compiler.compile(draft(a, inputs), trace(a), parameters));
    }

    @Test void leakInSemanticContextIsRejectedNotRewritten() throws IOException {
        CapabilityArtifact a = example();
        StepSpec open = a.steps().get(2);
        LocatorSpec l = open.locator();
        DiscoveryTrace t = new DiscoveryTrace();
        add(t, new StepSpec(open.id(), open.action(), new LocatorSpec(l.role(), l.accessibleName(), l.nameMatch(),
                new ContextSpec(ContextSpec.Kind.ROW, "Member 100042 Jane"), l.cardinality()), null, open.postcondition()), null);
        code(DISCOVERY_VALUE_LEAK, () -> compiler.compile(draft(a, a.inputs()), t, parameters()));
    }

    @Test void rejectedOrNonExecutableActionsCannotEnterTrace() throws IOException {
        CapabilityArtifact a = example();
        LocatorSpec l = a.steps().getFirst().locator();
        var fill = new UiAction(UUID.randomUUID(), UiAction.Type.FILL, "c0", "100042");
        DiscoveryTrace t = new DiscoveryTrace();
        for (ActionResult.Status status : List.of(ActionResult.Status.BLOCKED, ActionResult.Status.FAILED))
            code(INVALID_TRACE, () -> t.recordSuccessful(fill, new ActionResult(status, ActionResult.Code.VALUE_VERIFIED, null), l, null));
        code(INVALID_TRACE, () -> t.recordSuccessful(fill, new ActionResult(ActionResult.Status.SUCCEEDED,
                ActionResult.Code.CLICK_COMPLETED, null), l, null));
        code(INVALID_TRACE, () -> t.recordSuccessful(new UiAction(UUID.randomUUID(), UiAction.Type.COMPLETE, "", null),
                new ActionResult(ActionResult.Status.SUCCEEDED, ActionResult.Code.CHECKPOINT_VERIFIED, null), l, null));
        code(INVALID_TRACE, () -> compiler.compile(draft(a, a.inputs()), t, parameters()));
    }

    @Test void invalidAndMissingParametersFailClosed() throws IOException {
        CapabilityArtifact a = example();
        for (Object value : List.of("25", new BigDecimal("0"), new BigDecimal("101"), new BigDecimal("1.001"))) {
            var p = new LinkedHashMap<>(parameters());
            p.put("amount", value);
            code(INVALID_PARAMETER, () -> compiler.compile(draft(a, a.inputs()), trace(a), p));
        }
        var p = new LinkedHashMap<>(parameters());
        p.remove("reason");
        code(INVALID_PARAMETER, () -> compiler.compile(draft(a, a.inputs()), trace(a), p));
        p.put("reason", "x".repeat(121));
        code(INVALID_PARAMETER, () -> compiler.compile(draft(a, a.inputs()), trace(a), p));
    }

    @Test void versionsAndDuplicateIdsAreValidated() throws IOException {
        malformed(UNSUPPORTED_SCHEMA_VERSION, n -> n.put("schemaVersion", 2));
        malformed(INVALID_ARTIFACT_VERSION, n -> n.put("artifactVersion", 0));
        malformed(DUPLICATE_STEP_ID, n -> at(n, "/steps/1").put("id", "step-1"));
        ObjectNode n = (ObjectNode) mapper.readTree(exampleText());
        n.put("artifactVersion", 2);
        assertEquals(2, json.read(n.toString()).artifactVersion());
    }

    @ParameterizedTest @ValueSource(strings = {"literal", "${inputs}", "${inputs.}", "${inputs.memberId} extra",
            " ${inputs.memberId}", "${inputs.memberId.toString()}", "${env.KEY}", "${inputs.member-id}", "${inputs.memberId}${inputs.reason}"})
    void expressionsAreWholeAndStrict(String expression) throws IOException {
        malformed(INVALID_EXPRESSION, n -> at(n, "/steps/0").put("inputExpression", expression));
    }

    @Test void missingReferencesAndCheckpointAreRejected() throws IOException {
        malformed(MISSING_INPUT_REFERENCE, n -> at(n, "/steps/0").put("inputExpression", "${inputs.absent}"));
        malformed(MISSING_OUTPUT_REFERENCE, n -> at(n, "/checkpoint/extractors/0").put("output", "absent"));
        malformed(MISSING_INPUT_REFERENCE, n -> at(n, "/checkpoint/extractors/0").put("expectedInputExpression", "${inputs.absent}"));
        malformed(MISSING_CHECKPOINT, n -> n.remove("checkpoint"));
        malformed(MISSING_OUTPUT_EXTRACTOR, n -> at(n, "/checkpoint").putArray("extractors"));
        malformed(DUPLICATE_OUTPUT_EXTRACTOR, n -> at(n, "/checkpoint/extractors/1").put("output", "memberId"));
        malformed(INVALID_EXTRACTOR, n -> at(n, "/checkpoint/extractors/0").put("format", "USD_DECIMAL"));
        malformed(INVALID_EXTRACTOR, n -> at(n, "/checkpoint/extractors/0").put("expectedInputExpression", "${inputs.amount}"));
        malformed(INVALID_POSTCONDITION, n -> at(n, "/steps/0").remove("postcondition"));
    }

    @Test void invalidConstraintsAreRejected() throws IOException {
        malformed(INVALID_CONSTRAINTS, n -> at(n, "/inputs/reason/constraints").put("minLength", -1));
        malformed(INVALID_CONSTRAINTS, n -> at(n, "/inputs/reason/constraints").put("maxLength", 0));
        malformed(INVALID_CONSTRAINTS, n -> at(n, "/inputs/reason/constraints").put("maximum", 100));
        malformed(INVALID_CONSTRAINTS, n -> at(n, "/inputs/amount/constraints").put("minimum", 101));
        malformed(INVALID_CONSTRAINTS, n -> at(n, "/inputs/amount/constraints").put("maxScale", 9));
        malformed(INVALID_CONSTRAINTS, n -> at(n, "/inputs/amount/constraints").put("maxLength", 8));
        malformed(INVALID_CONSTRAINTS, n -> at(n, "/outputs/amount/constraints").remove("maximum"));
        malformed(INVALID_CONSTRAINTS, n -> at(n, "/outputs/amount/constraints").put("minimum", 0.001));
    }

    @ParameterizedTest @ValueSource(strings = {"Preview request", "Continue", "Review changes"})
    void unfamiliarClickNamesAreStructurallyValid(String name) throws IOException {
        ObjectNode n = (ObjectNode) mapper.readTree(exampleText());
        at(n, "/steps/7/locator").put("accessibleName", name);
        assertEquals(name, json.read(n.toString()).steps().getLast().locator().accessibleName());
    }

    @Test void compilerAlsoRejectsInvalidActionStructure() throws IOException {
        CapabilityArtifact a = example();
        StepSpec s = a.steps().getLast();
        LocatorSpec l = s.locator();
        DiscoveryTrace t = new DiscoveryTrace();
        add(t, new StepSpec(s.id(), s.action(), new LocatorSpec(LocatorSpec.Role.HEADING, "Review changes", l.nameMatch(), null,
                l.cardinality()), null, s.postcondition()), null);
        code(UNSAFE_ACTION, () -> compiler.compile(draft(a, a.inputs()), t, parameters()));
    }

    @Test void locatorsMustBeBoundedSemanticAndExactOne() throws IOException {
        malformed(UNBOUNDED_LOCATOR_TEXT, n -> at(n, "/steps/0/locator").put("accessibleName", "x".repeat(161)));
        malformed(UNBOUNDED_LOCATOR_TEXT, n -> at(n, "/steps/2/locator/context").put("text", "x".repeat(301)));
        malformed(UNBOUNDED_LOCATOR_TEXT, n -> at(n, "/steps/0/locator").put("accessibleName", "\n"));
        malformed(INVALID_LOCATOR, n -> at(n, "/steps/0/locator").remove("cardinality"));
        malformed(INVALID_LOCATOR, n -> at(n, "/steps/0/locator").put("nameMatch", "PREFIX"));
        malformed(MISSING_INPUT_REFERENCE, n -> at(n, "/steps/2/locator/context").put("text", "${inputs.absent}"));
        malformed(UNBOUNDED_LOCATOR_TEXT, n -> at(n, "/inputs/memberId/constraints").put("maxLength", 301));
        malformed(INVALID_EXPRESSION, n -> at(n, "/steps/2/locator/context").put("text", "Member ${inputs.memberId}"));
    }

    @Test void malformedJsonAndRuntimeFieldsFailWithoutEchoingPayload() throws IOException {
        for (String field : List.of("css", "xpath", "controlId", "handle", "transcript", "value"))
            malformed(MALFORMED_ARTIFACT, n -> at(n, "/steps/0/locator").put(field, "SECRET"));
        malformed(MALFORMED_ARTIFACT, n -> at(n, "/steps/0").put("action", "SUBMIT"));
        malformed(MALFORMED_ARTIFACT, n -> at(n, "/steps/0").put("action", 0));
        malformed(MALFORMED_ARTIFACT, n -> n.put("schemaVersion", "1"));
        malformed(MALFORMED_ARTIFACT, n -> n.put("schemaVersion", 1.5));
        malformed(MALFORMED_ARTIFACT, n -> at(n, "/inputs/amount/constraints").put("minimum", "0.01"));
        malformed(MALFORMED_ARTIFACT, n -> n.putNull("schemaVersion"));
        code(MALFORMED_ARTIFACT, () -> json.read("{SECRET"));
        String duplicate = exampleText().replaceFirst("\\{", "{\"schemaVersion\":1,");
        code(MALFORMED_ARTIFACT, () -> json.read(duplicate));
        String trailing = exampleText() + " {}";
        code(MALFORMED_ARTIFACT, () -> json.read(trailing));
        code(MALFORMED_ARTIFACT, () -> json.read("x".repeat(128001)));
    }

    @Test void targetAndProvenanceAreValidated() throws IOException {
        for (String target : List.of("", " ", "x".repeat(81), "file:///tmp", "http://host", "bank\n"))
            malformed(INVALID_TARGET, n -> at(n, "/target").put("targetId", target));
        malformed(INVALID_TARGET, n -> at(n, "/target").remove("targetId"));
        malformed(MALFORMED_ARTIFACT, n -> at(n, "/target").put("origin", "http://localhost:8080"));
        for (String path : List.of("//evil", "/../legacy", "/legacy?member=SECRET", "/%2e%2e/"))
            malformed(INVALID_TARGET, n -> at(n, "/target").put("entryPath", path));
        malformed(INVALID_PROVENANCE, n -> at(n, "/provenance").put("source", "COMPILED_TRACE"));
    }

    @Test void metadataIsRequiredBoundedAndPrintable() throws IOException {
        for (String pointer : List.of("", "/inputs/memberId", "/outputs/memberId")) {
            String field = pointer.isEmpty() ? "displayName" : "description";
            int max = pointer.isEmpty() ? 160 : 500;
            malformed(INVALID_METADATA, n -> at(n, pointer).remove(field));
            malformed(INVALID_METADATA, n -> at(n, pointer).putNull(field));
            for (String text : List.of("", "   ", "bad\ntext", "x".repeat(max + 1)))
                malformed(INVALID_METADATA, n -> at(n, pointer).put(field, text));
        }
        malformed(INVALID_METADATA, n -> n.remove("description"));
        for (String text : List.of("", " ", "bad\ttext", "x".repeat(1001)))
            malformed(INVALID_METADATA, n -> n.put("description", text));
        for (String name : example().inputs().keySet())
            malformed(INVALID_METADATA, n -> at(n, "/inputs/" + name).remove("description"));
        for (String name : example().outputs().keySet())
            malformed(INVALID_METADATA, n -> at(n, "/outputs/" + name).remove("description"));
        malformed(MALFORMED_ARTIFACT, n -> n.put("displayName", 42));
        malformed(MALFORMED_ARTIFACT, n -> at(n, "/inputs/memberId").put("description", true));
    }

    @Test void executionBoundaryAndOutcomeDeclarationsFailClosed() throws IOException {
        malformed(INVALID_EXECUTION_BOUNDARY, n -> n.remove("executionBoundary"));
        malformed(INVALID_EXECUTION_BOUNDARY, n -> at(n, "/executionBoundary").remove("mode"));
        malformed(INVALID_EXECUTION_BOUNDARY, n -> at(n, "/executionBoundary").putNull("mode"));
        for (String mode : List.of("", "SUBMIT", "review_only", "READ_WRITE"))
            malformed(MALFORMED_ARTIFACT, n -> at(n, "/executionBoundary").put("mode", mode));
        malformed(MALFORMED_ARTIFACT, n -> at(n, "/executionBoundary").put("allowSubmit", true));
        malformed(REQUIRED_FIELD, n -> n.remove("outcomes"));
        malformed(REQUIRED_FIELD, n -> n.putArray("outcomes"));
        malformed(DUPLICATE_OUTCOME_CODE, n -> at(n, "/outcomes/1").put("code", "MEMBER_NOT_FOUND"));
        malformed(INVALID_POSTCONDITION, n -> at(n, "/outcomes/0/condition/locator").put("role", "HEADING"));
        CapabilityArtifact a = example();
        assertEquals(List.of("MEMBER_NOT_FOUND", "VALIDATION_REJECTED"), a.outcomes().stream().map(OutcomeSpec::code).toList());
        assertNotEquals(a.outcomes().get(0).condition(), a.outcomes().get(1).condition());
        for (OutcomeSpec outcome : a.outcomes()) assertNotEquals(a.checkpoint().marker(), outcome.condition().locator());
    }

    @Test void structuralAcceptanceNeverAuthorizesRuntimeControlsOrDestinations() throws IOException {
        ObjectNode n = (ObjectNode) mapper.readTree(exampleText());
        at(n, "/steps/7/locator").put("accessibleName", "Preview request");
        CapabilityArtifact a = json.read(n.toString());
        String name = a.steps().getLast().locator().accessibleName();
        String origin = "http://localhost:8080";
        String current = origin + "/legacy";
        var click = new UiAction(UUID.randomUUID(), UiAction.Type.CLICK, "c0", null);
        var empty = new ActionPolicy(origin, List.of(Pattern.compile("/.*")), Set.of(UiAction.Type.CLICK), Map.of());
        assertFalse(empty.permits(click, current, current, name));
        var configured = new ActionPolicy(origin, empty.routes(), empty.actions(),
                Map.of(UiAction.Type.CLICK, Set.of(name, "Submit reversal")));
        assertTrue(configured.permits(click, current, current, name));
        for (String path : List.of("/submit", "/legacy/submit", "/legacy/submit/", "/legacy/submit/next",
                "/legacy/Submit", "/legacy/submit;session=x", "/legacy/%73ubmit", "/legacy/submit?x=1",
                "/legacy/accounts/SAV-2048/fee-reversal/submit")) {
            assertFalse(configured.permits(click, current, origin + path, name), path);
            assertFalse(configured.permits(click, current, origin + path, "Submit reversal"), path);
        }
        assertFalse(configured.permits(click, current, "https://other.example/legacy", name));
        var fill = new UiAction(UUID.randomUUID(), UiAction.Type.FILL, "c0", "value");
        var fills = new ActionPolicy(origin, empty.routes(), Set.of(UiAction.Type.FILL),
                Map.of(UiAction.Type.FILL, Set.of("Configured input")));
        assertFalse(fills.permits(fill, current, null, "Unknown input"));
        assertTrue(fills.permits(fill, current, null, "Configured input"));
        assertFalse(fills.permits(fill, current, origin + "/submit", "Configured input"));
    }

    @Test void compilerChecksNewMetadataForValueLeaks() throws IOException {
        CapabilityArtifact a = example();
        var leaking = new CapabilityArtifact(a.schemaVersion(), a.artifactVersion(), a.capabilityId(),
                a.displayName(), "For member 100042", a.executionBoundary(), a.target(), a.inputs(), a.outputs(),
                List.of(), a.outcomes(), a.checkpoint(), a.provenance());
        code(DISCOVERY_VALUE_LEAK, () -> compiler.compile(leaking, trace(a), parameters()));
    }

    @Test void writeBoundaryValidatesAndTraceIsNotASerializableDto() throws IOException {
        CapabilityArtifact a = example();
        CapabilityArtifact invalid = new CapabilityArtifact(99, 1, a.capabilityId(), a.displayName(), a.description(), a.executionBoundary(), a.target(), a.inputs(),
                a.outputs(), a.steps(), a.outcomes(), a.checkpoint(), a.provenance());
        code(UNSUPPORTED_SCHEMA_VERSION, () -> json.write(invalid));
        assertThrows(RuntimeException.class, () -> mapper.writeValueAsString(trace(a)));
    }
}
