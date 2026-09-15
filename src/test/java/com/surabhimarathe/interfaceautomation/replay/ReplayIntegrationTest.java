package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.artifact.*;
import com.surabhimarathe.interfaceautomation.discovery.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import jakarta.servlet.Filter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static com.surabhimarathe.interfaceautomation.replay.ReplayResult.Code.*;
import static com.surabhimarathe.interfaceautomation.replay.ReplayResult.Status.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReplayIntegrationTest.RequestCounts.class)
class ReplayIntegrationTest {
    @LocalServerPort int port;
    @TempDir Path temp;
    static final AtomicInteger submits = new AtomicInteger();
    static final AtomicInteger reviews = new AtomicInteger();
    static volatile long delayEntry, delayReview;
    static volatile String entryHtml;
    final ObjectMapper mapper = new ObjectMapper();
    final ReplayOptions options = new ReplayOptions(Duration.ofSeconds(5), Duration.ofSeconds(25), true);
    @BeforeEach void reset() { submits.set(0); reviews.set(0); delayEntry = 0; delayReview = 0; entryHtml = null; }
    @AfterEach void resetDelay() { delayEntry = 0; delayReview = 0; entryHtml = null; }

    @TestConfiguration static class RequestCounts {
        @Bean FilterRegistrationBean<Filter> replayRequestCounts() {
            FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>();
            bean.setFilter((request, response, chain) -> {
                var http = (jakarta.servlet.http.HttpServletRequest) request;
                if (http.getRequestURI().endsWith("/submit")) submits.incrementAndGet();
                if (http.getRequestURI().endsWith("/review")) reviews.incrementAndGet();
                if (http.getRequestURI().endsWith("/review") && delayReview > 0) {
                    try { Thread.sleep(delayReview); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                }
                if (http.getRequestURI().equals("/legacy") && delayEntry > 0) {
                    try { Thread.sleep(delayEntry); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                }
                if (http.getRequestURI().equals("/legacy") && entryHtml != null) {
                    response.setContentType("text/html;charset=UTF-8");
                    response.getWriter().write(entryHtml);
                    return;
                }
                chain.doFilter(request, response);
            });
            return bean;
        }
    }

    String fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/artifacts/prepare-fee-reversal-review.v1.json")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    String changed(Consumer<ObjectNode> change) throws Exception {
        ObjectNode node = (ObjectNode) mapper.readTree(fixture());
        change.accept(node);
        return node.toString();
    }
    ObjectNode at(ObjectNode node, String path) { return (ObjectNode) node.at(path); }
    InvocationParameters parameters(String member, String reason) {
        return new InvocationParameters(Map.of("memberId", member, "amount", new BigDecimal("25.00"), "reason", reason));
    }
    InvocationParameters parameters() { return parameters("100042", "Courtesy adjustment"); }
    ActionPolicy policy() throws Exception {
        Properties props = new Properties();
        try (var in = getClass().getResourceAsStream("/application.properties")) { props.load(in); }
        var env = new MockEnvironment();
        props.forEach((k,v) -> env.withProperty((String) k, (String) v));
        env.withProperty("discovery.allowed-origin", "http://localhost:" + port);
        return DiscoveryPolicyConfiguration.from(env);
    }
    ReplayEngine engine() throws Exception { return new ReplayEngine(TargetRegistry.singleTenant(new TenantId("synthetic-local"), Map.of("legacy-banking", policy())), options); }
    void failure(ReplayResult result, ReplayResult.Code code) {
        assertEquals(code == POLICY_DENIED ? BLOCKED : FAILED, result.status(), result.toString());
        assertEquals(code, result.code(), result.toString());
        assertTrue(result.outputs().isEmpty());
        assertNull(result.outcomeCode());
        assertNotNull(result.diagnostic());
        assertEquals(result.step(), result.diagnostic().step());
        assertEquals(code, result.diagnostic().code());
        assertEquals(code == POLICY_DENIED ? ReplayResult.Disposition.POLICY_BLOCK :
                code == TIMEOUT ? ReplayResult.Disposition.RECOVERABLE : ReplayResult.Disposition.HARD_FAILURE, result.disposition());
        for (String secret : List.of("100042", "Courtesy", "http", "Morgan", "1842"))
            assertFalse(result.toString().contains(secret));
    }

    @Test void fixtureReturnsSevenExactTypedOutputsWithoutSubmitting() throws Exception {
        ReplayResult result = engine().runValidation(fixture(), parameters());
        assertEquals(SUCCEEDED, result.status(), result.toString());
        assertEquals(CHECKPOINT_VERIFIED, result.code());
        assertEquals(ReplayResult.Disposition.SUCCESS, result.disposition());
        assertNull(result.diagnostic());
        assertTrue(Map.of("memberId", "100042", "memberName", "Morgan Lee", "accountId", "SAV-2048",
                "currentBalance", new BigDecimal("1842.73"), "amount", new BigDecimal("25.00"),
                "reason", "Courtesy adjustment", "projectedBalance", new BigDecimal("1867.73")).equals(result.outputs()), "Seven exact typed outputs must match");
        assertEquals(8, result.step());
        assertEquals(0, submits.get());
        assertEquals(1, reviews.get(), "The review click must not be retried");
        assertFalse(result.toString().contains("Morgan"));
        assertFalse(parameters().toString().contains("100042"));
    }

    @Test void missingMemberPrecedesBadPostconditionAndMissingNextLocator() throws Exception {
        String artifact = changed(n -> {
            at(n, "/steps/1/postcondition/locator").put("accessibleName", "Wrong success heading");
            at(n, "/steps/2/locator").put("accessibleName", "Missing control");
        });
        ReplayResult result = engine().runValidation(artifact, parameters("999999", "Courtesy adjustment"));
        assertEquals(EXPECTED_OUTCOME, result.status(), result.toString());
        assertEquals(BUSINESS_OUTCOME, result.code());
        assertEquals(ReplayResult.Disposition.EXPECTED_OUTCOME, result.disposition());
        assertEquals(ReplayDiagnostic.Phase.OUTCOME, result.diagnostic().phase());
        assertEquals("Member not found", result.diagnostic().expected().name());
        assertTrue(result.diagnostic().conditions().outcomeDetected());
        assertEquals("MEMBER_NOT_FOUND", result.outcomeCode());
        assertEquals("MEMBER_NOT_FOUND", result.effectiveCode());
        assertTrue(result.toString().contains("code=MEMBER_NOT_FOUND"));
        assertFalse(result.toString().contains("999999"));
        assertFalse(result.toString().contains("Courtesy adjustment"));
        assertEquals(2, result.step());
        assertTrue(result.outputs().isEmpty());
        assertEquals(0, reviews.get());
    }

    @Test void validationRejectedIsAnExpectedOutcomeNotAPostconditionFailure() throws Exception {
        ReplayResult result = engine().runValidation(fixture(), parameters("100042", " "));
        assertEquals(EXPECTED_OUTCOME, result.status(), result.toString());
        assertEquals("VALIDATION_REJECTED", result.outcomeCode());
        assertTrue(result.toString().contains("code=VALIDATION_REJECTED"));
        assertFalse(result.toString().contains("100042"));
        assertEquals(8, result.step());
        assertTrue(result.outputs().isEmpty());
    }

    @Test void missingRowContextMakesSecondOpenAmbiguous() throws Exception {
        String artifact = changed(n -> at(n, "/steps/3/locator").putNull("context"));
        ReplayResult result = engine().runValidation(artifact, parameters());
        failure(result, AMBIGUOUS_LOCATOR);
        assertEquals(4, result.step());
        assertEquals("step-4", result.diagnostic().stepId());
        assertEquals(ReplayDiagnostic.Phase.LOCATOR, result.diagnostic().phase());
        assertEquals("Open", result.diagnostic().expected().name());
        assertEquals(LocatorSpec.Role.LINK, result.diagnostic().expected().role());
        assertEquals(LocatorSpec.Cardinality.EXACT_ONE, result.diagnostic().expected().cardinality());
        assertNull(result.diagnostic().expected().context());
        assertEquals(2, result.diagnostic().matches().visible());
        assertTrue(result.diagnostic().matches().ambiguous());
    }

    @Test void missingLocatorAndWrongPostconditionHaveDistinctCodes() throws Exception {
        String missing = changed(n -> {
            at(n, "/steps/0/locator").put("accessibleName", "Absent field");
            at(n, "/steps/0/postcondition/locator").put("accessibleName", "Absent field");
        });
        var missingResult = engine().runValidation(missing, parameters());
        failure(missingResult, ZERO_LOCATOR);
        assertEquals(ReplayDiagnostic.Phase.LOCATOR, missingResult.diagnostic().phase());
        assertEquals("step-1", missingResult.diagnostic().stepId());
        assertEquals("Absent field", missingResult.diagnostic().expected().name());
        assertTrue(missingResult.diagnostic().matches().zero());
        assertEquals(0, missingResult.diagnostic().matches().visible());
        String post = changed(n -> at(n, "/steps/1/postcondition/locator").put("accessibleName", "Absent heading"));
        var postResult = engine().runValidation(post, parameters());
        failure(postResult, POSTCONDITION_FAILED);
        assertEquals(ReplayDiagnostic.Phase.POSTCONDITION, postResult.diagnostic().phase());
        assertEquals("Absent heading", postResult.diagnostic().expected().name());
        assertEquals("step-2", postResult.diagnostic().stepId());
        assertTrue(postResult.diagnostic().conditions().postconditionFailed());
        assertTrue(postResult.diagnostic().matches().zero());
    }

    @Test void checkpointAndExtractionMustBothVerify() throws Exception {
        var checkpoint = engine().runValidation(changed(n -> at(n, "/checkpoint/marker").put("accessibleName", "Absent checkpoint")), parameters());
        failure(checkpoint, CHECKPOINT_FAILED);
        assertEquals(ReplayDiagnostic.Phase.CHECKPOINT, checkpoint.diagnostic().phase());
        assertEquals("step-8", checkpoint.diagnostic().stepId());
        assertEquals("Absent checkpoint", checkpoint.diagnostic().expected().name());
        assertTrue(checkpoint.diagnostic().conditions().checkpointFailed());
        assertTrue(checkpoint.diagnostic().matches().zero());
        failure(engine().runValidation(changed(n -> at(n, "/outputs/memberName/constraints").put("maxLength", 3)),
                parameters()), EXTRACTION_FAILED);
        failure(engine().runValidation(changed(n -> at(n, "/checkpoint/extractors/0/locator").put("accessibleName", "Missing row")),
                parameters()), EXTRACTION_FAILED);
    }

    @Test void configuredSubmitControlStillBlockedBeforeAnyRequestAndBalanceUnchanged() throws Exception {
        String artifact = changed(n -> {
            var extra = at(n, "/steps/7").deepCopy();
            extra.put("id", "never-submit");
            at(extra, "/locator").put("accessibleName", "Submit reversal");
            ((tools.jackson.databind.node.ArrayNode) n.get("steps")).add(extra);
        });
        ActionPolicy base = policy();
        var names = new HashMap<>(base.controlNames());
        var clicks = new HashSet<>(names.get(UiAction.Type.CLICK));
        clicks.add("Submit reversal");
        names.put(UiAction.Type.CLICK, clicks);
        var broad = new ActionPolicy(base.origin(), List.of(Pattern.compile("/legacy(?:/.*)?")), base.actions(), names);
        ReplayEngine engine = new ReplayEngine(TargetRegistry.singleTenant(new TenantId("synthetic-local"), Map.of("legacy-banking", broad)), options);
        ReplayResult result = engine.runValidation(artifact, parameters());
        failure(result, POLICY_DENIED);
        assertEquals(9, result.step());
        assertTrue(result.diagnostic().conditions().policyDenied());
        assertFalse(result.diagnostic().conditions().actionStarted());
        assertEquals("never-submit", result.diagnostic().stepId());
        assertEquals(0, submits.get());
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base.origin()
                + "/legacy/accounts/SAV-2048")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.body().contains("$1842.73"));
    }

    @Test void invalidArtifactParametersUnknownTargetAndEntryPolicyFailBeforeLaunch() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        ReplayEngine engine = new ReplayEngine(TargetRegistry.singleTenant(new TenantId("synthetic-local"), Map.of("legacy-banking", policy())), options,
                (p, o) -> { launches.incrementAndGet(); throw new IllegalStateException("PRIVATE"); });
        failure(engine.runValidation("{secret", parameters()), INVALID_ARTIFACT);
        for (Object amount : List.of("25.00", new BigDecimal("0"), new BigDecimal("100.01"), new BigDecimal("1.001"))) {
            var values = new HashMap<>(parameters().values());
            values.put("amount", amount);
            failure(engine.runValidation(fixture(), new InvocationParameters(values)), INVALID_PARAMETERS);
        }
        var extra = new HashMap<>(parameters().values());
        extra.put("extra", "PRIVATE");
        failure(engine.runValidation(fixture(), new InvocationParameters(extra)), INVALID_PARAMETERS);
        failure(engine.runValidation(fixture(), new InvocationParameters(Map.of())), INVALID_PARAMETERS);
        failure(engine.runValidation(changed(n -> at(n, "/target").put("targetId", "unknown")), parameters()), UNKNOWN_TARGET);
        failure(engine.runValidation(changed(n -> at(n, "/target").put("origin", "http://evil.example")), parameters()), INVALID_ARTIFACT);
        failure(engine.runValidation(changed(n -> at(n, "/target").put("entryPath", "/admin")), parameters()), POLICY_DENIED);
        assertEquals(0, launches.get());
    }

    @Test void browserExceptionsAreRedacted() throws Exception {
        var engine = new ReplayEngine(TargetRegistry.singleTenant(new TenantId("synthetic-local"), Map.of("legacy-banking", policy())), options,
                (p,o) -> { throw new IllegalStateException("PRIVATE https://data/100042"); });
        failure(engine.runValidation(fixture(), parameters()), BROWSER_FAILURE);
    }

    @Test void perStepAndOverallDeadlinesAreBounded() throws Exception {
        delayEntry = 1200;
        var registry = TargetRegistry.singleTenant(new TenantId("synthetic-local"), Map.of("legacy-banking", policy()));
        var stepBound = new ReplayEngine(registry, new ReplayOptions(Duration.ofMillis(150), Duration.ofSeconds(10), true));
        var slow = stepBound.runValidation(fixture(), parameters());
        failure(slow, TIMEOUT);
        assertEquals(ReplayDiagnostic.Phase.LOAD, slow.diagnostic().phase());
        assertEquals(0, slow.diagnostic().step());
        assertNull(slow.diagnostic().stepId());
        assertEquals(-1, slow.diagnostic().matches().visible());
        assertTrue(slow.diagnostic().conditions().timedOut());
        assertFalse(slow.diagnostic().conditions().actionStarted());
        long start = System.nanoTime();
        var overallBound = new ReplayEngine(registry, new ReplayOptions(Duration.ofSeconds(5), Duration.ofMillis(100), true));
        failure(overallBound.runValidation(fixture(), parameters()), TIMEOUT);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
        assertEquals(0, reviews.get());
    }

    @Test void outcomeOnEntryPrecedesAnyStepLocator() throws Exception {
        entryHtml = "<main><div role='status' aria-label='Member not found'>No matching record.</div></main>";
        ReplayResult result = engine().runValidation(fixture(), parameters());
        assertEquals(EXPECTED_OUTCOME, result.status(), result.toString());
        assertEquals("MEMBER_NOT_FOUND", result.effectiveCode());
        assertEquals(1, result.step());
        assertEquals(0, reviews.get());
    }

    @Test void sharedInterceptionBlocksScriptInitiatedSubmitBeforeServerReceivesIt() throws Exception {
        entryHtml = """
            <main><h1>Member Search</h1>
            <form method="post" action="/legacy/members/search">
            <input aria-label="Member ID" name="memberId">
            <button type="submit" onclick="fetch('/legacy/accounts/SAV-2048/fee-reversal/submit',{method:'POST'})">Search</button>
            </form></main>
            """;
        var blocked = engine().runValidation(fixture(), parameters());
        failure(blocked, POLICY_DENIED);
        assertTrue(blocked.diagnostic().conditions().requestDenied());
        assertEquals(0, submits.get());
    }

    @Test void slowReviewIsRecoverableButDoesNotRetryTheClick() throws Exception {
        delayReview = 1500;
        var bounded = new ReplayEngine(TargetRegistry.singleTenant(new TenantId("synthetic-local"), Map.of("legacy-banking",policy())),
                new ReplayOptions(Duration.ofMillis(700),Duration.ofSeconds(20),true));
        var result = bounded.runValidation(fixture(),parameters());
        failure(result,TIMEOUT);
        assertEquals(8,result.step());
        assertEquals(ReplayDiagnostic.Phase.ACTION,result.diagnostic().phase());
        assertEquals("Review reversal",result.diagnostic().expected().name());
        assertTrue(result.diagnostic().conditions().actionStarted());
        assertEquals(1,reviews.get(),"The review POST must not be retried");
        assertEquals(0,submits.get());
    }

    @Test void unexpectedDialogDuringLoadIsHardFailureWithoutText() throws Exception {
        entryHtml = "<script>alert('PRIVATE_DIALOG https://private/100042')</script><h1>Member Search</h1>";
        var result = engine().runValidation(fixture(),parameters());
        failure(result,UNEXPECTED_DIALOG);
        assertEquals(0,result.step());
        assertEquals(ReplayDiagnostic.Phase.LOAD,result.diagnostic().phase());
        assertTrue(result.diagnostic().conditions().unexpectedDialog());
        assertFalse(result.diagnostic().conditions().policyDenied());
        assertFalse(result.toString().contains("PRIVATE"));
        assertFalse(mapper.writeValueAsString(result.diagnostic()).contains("PRIVATE"));
        assertEquals(0,reviews.get());
    }

    @Test void unexpectedDialogDuringClickStopsBeforePostconditionOrNextAction() throws Exception {
        entryHtml = "<h1>Member Search</h1><form action='/legacy/members/search' method='post'>"
                + "<input aria-label='Member ID' name='memberId'>"
                + "<button onclick=\"alert('PRIVATE_DIALOG');event.preventDefault()\">Search</button></form>";
        var result = engine().runValidation(fixture(),parameters());
        failure(result,UNEXPECTED_DIALOG);
        assertEquals(2,result.step());
        assertEquals(ReplayDiagnostic.Phase.ACTION,result.diagnostic().phase());
        assertTrue(result.diagnostic().conditions().unexpectedDialog());
        assertTrue(result.diagnostic().conditions().actionStarted());
        assertEquals(0,reviews.get());
    }

    @Test void diagnosticStoreIsOptInAndSuccessWritesNothing() throws Exception {
        Path directory = temp.resolve("success-diagnostics");
        var storedEngine = new ReplayEngine(TargetRegistry.singleTenant(new TenantId("synthetic-local"), Map.of("legacy-banking",policy())),options,new DiagnosticStore(directory));
        var success = storedEngine.runValidation(fixture(),parameters());
        assertEquals(SUCCEEDED,success.status());
        assertEquals(ReplayResult.DiagnosticPersistence.NOT_APPLICABLE,success.diagnosticPersistence());
        assertNull(success.diagnostic());
        assertFalse(Files.exists(directory));
        var failure = engine().runValidation(changed(n -> at(n,"/steps/0/locator").put("accessibleName","Absent field")),parameters());
        assertEquals(ReplayResult.DiagnosticPersistence.DISABLED,failure.diagnosticPersistence());
        assertFalse(Files.exists(directory));
    }

    @Test void diagnosticPersistenceIsRedactedAndCollisionCannotOverwrite() throws Exception {
        Path directory = temp.resolve("diagnostics");
        UUID id = UUID.randomUUID();
        var store = new DiagnosticStore(directory,() -> id);
        var storedEngine = new ReplayEngine(TargetRegistry.singleTenant(new TenantId("synthetic-local"), Map.of("legacy-banking",policy())),options,store);
        String artifact = changed(n -> at(n,"/steps/3/locator").putNull("context"));
        var first = storedEngine.runValidation(artifact,parameters());
        failure(first,AMBIGUOUS_LOCATOR);
        assertEquals(ReplayResult.DiagnosticPersistence.STORED,first.diagnosticPersistence());
        Path file = directory.resolve("diagnostic-"+id+".json");
        byte[] original = Files.readAllBytes(file);
        String json = Files.readString(file);
        assertEquals(first.diagnostic(),mapper.readValue(json,ReplayDiagnostic.class));
        for (String secret : List.of("100042","Morgan Lee","SAV-2048","25.00","1842.73","Courtesy adjustment",
                "http","<html","xpath","selector","exception","screenshot","OPENROUTER","outputs"))
            assertFalse(json.contains(secret),"Diagnostic contains forbidden data");
        var second = storedEngine.runValidation(artifact,parameters());
        failure(second,AMBIGUOUS_LOCATOR);
        assertEquals(ReplayResult.DiagnosticPersistence.COLLISION,second.diagnosticPersistence());
        assertArrayEquals(original,Files.readAllBytes(file));
        try (var files = Files.list(directory)) { assertEquals(1,files.count()); }
    }

    @Test void parameterizedExpectationStaysUnexpandedAndSensitiveArtifactLiteralIsRedacted() throws Exception {
        String dynamic = changed(n -> {
            at(n,"/steps/0/locator").put("accessibleName","${inputs.memberId}");
            at(n,"/steps/0/postcondition/locator").put("accessibleName","${inputs.memberId}");
        });
        var result = engine().runValidation(dynamic,parameters());
        failure(result,ZERO_LOCATOR);
        assertEquals("${inputs.memberId}",result.diagnostic().expected().name());
        assertFalse(mapper.writeValueAsString(result.diagnostic()).contains("100042"));
        String literal = changed(n -> {
            at(n,"/steps/0/locator").put("accessibleName","Courtesy adjustment");
            at(n,"/steps/0/postcondition/locator").put("accessibleName","Courtesy adjustment");
        });
        var redacted = engine().runValidation(literal,parameters());
        failure(redacted,ZERO_LOCATOR);
        assertEquals("[REDACTED]",redacted.diagnostic().expected().name());
        assertFalse(mapper.writeValueAsString(redacted.diagnostic()).contains("Courtesy"));
    }

    @Test void missingParameterizedRowContextIsNeverResolvedInDiagnostics() throws Exception {
        String artifact = changed(n -> {
            var context = mapper.createObjectNode().put("kind","ROW").put("text","${inputs.memberId}");
            at(n,"/steps/0/locator").set("context",context);
            at(n,"/steps/0/postcondition/locator").set("context",context.deepCopy());
        });
        var result = engine().runValidation(artifact,parameters());
        failure(result,ZERO_LOCATOR);
        assertEquals(ContextSpec.Kind.ROW,result.diagnostic().expected().context().kind());
        assertEquals("${inputs.memberId}",result.diagnostic().expected().context().text());
        assertFalse(mapper.writeValueAsString(result.diagnostic()).contains("100042"));
    }

    @Test void unexpectedPopupRemainsNonRetryableAndIsNotReportedAsADialog() throws Exception {
        entryHtml = "<h1>Member Search</h1><form action='/legacy/members/search' method='post'>"
                + "<input aria-label='Member ID' name='memberId'>"
                + "<button onclick=\"window.open('about:blank');event.preventDefault()\">Search</button></form>";
        var result = engine().runValidation(fixture(),parameters());
        failure(result,BROWSER_FAILURE);
        assertTrue(result.diagnostic().conditions().unexpectedPage());
        assertFalse(result.diagnostic().conditions().unexpectedDialog());
        assertEquals(0,reviews.get());
    }

    @Test void persistenceFailureCannotChangeReplayClassificationOrLeakPath() throws Exception {
        Path notDirectory = temp.resolve("PRIVATE_PATH");
        Files.writeString(notDirectory,"original");
        var storedEngine = new ReplayEngine(TargetRegistry.singleTenant(new TenantId("synthetic-local"), Map.of("legacy-banking",policy())),options,new DiagnosticStore(notDirectory));
        var result = storedEngine.runValidation(changed(n -> at(n,"/steps/3/locator").putNull("context")),parameters());
        failure(result,AMBIGUOUS_LOCATOR);
        assertEquals(ReplayResult.DiagnosticPersistence.FAILED,result.diagnosticPersistence());
        assertFalse(result.toString().contains("PRIVATE"));
        assertEquals("original",Files.readString(notDirectory));
    }

    @Test void formAndFieldsetContextsSelectExactlyOneControl() throws Exception {
        for (String kind : List.of("FORM", "FIELDSET")) {
            String tag = kind.toLowerCase(Locale.ROOT);
            entryHtml = "<main><h1>Member Search</h1><" + tag + ">Preferred group<input aria-label='Member ID'></" + tag
                    + "><" + tag + ">Other group<input aria-label='Member ID'></" + tag + "></main>";
            String artifact = singleStep(n -> {
                ObjectNode context = mapper.createObjectNode().put("kind", kind).put("text", "Preferred group");
                at(n, "/steps/0/locator").set("context", context);
                at(n, "/steps/0/postcondition/locator").set("context", context.deepCopy());
            });
            ReplayResult result = engine().runValidation(artifact, parameters());
            assertEquals(SUCCEEDED, result.status(), result.toString());
        }
    }

    @Test void prefixNamesAreLiteralAcrossJavaAndJavaScriptRegexEngines() throws Exception {
        entryHtml = "<main><h1>Member Search</h1><input aria-label='Member ID'>"
                + "<table><tr><th>[Balance]</th><td>Selected</td></tr><tr><th>B</th><td>Other</td></tr></table></main>";
        String artifact = singleStep(n -> {
            var extractor = at(n, "/checkpoint/extractors/0");
            at(extractor, "/locator").put("role", "ROW").put("accessibleName", "[Balance]").put("nameMatch", "PREFIX");
            extractor.put("read", "ROW_VALUE");
        });
        ReplayResult result = engine().runValidation(artifact, parameters());
        assertEquals(SUCCEEDED, result.status(), result.toString());
        assertTrue(Map.of("demo", "Selected").equals(result.outputs()), "Literal prefix must select only its row");
    }

    private String singleStep(Consumer<ObjectNode> additional) throws Exception {
        return changed(n -> {
            var step = at(n, "/steps/0").deepCopy();
            n.putArray("steps").add(step);
            var outputs = n.putObject("outputs");
            outputs.putObject("demo").put("type", "STRING").put("description", "Test text output")
                    .putObject("constraints").put("minLength", 1).put("maxLength", 160);
            var checkpoint = at(n, "/checkpoint");
            at(checkpoint, "/marker").put("accessibleName", "Member Search");
            var extractor = checkpoint.putArray("extractors").addObject();
            extractor.put("output", "demo").put("read", "TEXT").put("format", "TEXT");
            extractor.set("locator", at(checkpoint, "/marker").deepCopy());
            additional.accept(n);
        });
    }

    @Test void replaySucceedsWithModelClassesUnavailableAndMakesZeroModelCalls() throws Exception {
        AtomicInteger modelLoads = new AtomicInteger();
        URL classes = ReplayEngine.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader isolated = new URLClassLoader(new URL[]{classes}, getClass().getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.contains("OpenRouterClient") || name.endsWith(".DecisionClient")
                        || name.endsWith(".DiscoveryFlow") || name.endsWith(".DiscoveryProof")) {
                    modelLoads.incrementAndGet();
                    throw new ClassNotFoundException("MODEL_UNAVAILABLE");
                }
                if (name.startsWith("com.surabhimarathe.interfaceautomation.replay.")) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> registryType = isolated.loadClass(TargetRegistry.class.getName());
            Class<?> tenantType = isolated.loadClass(TenantId.class.getName());
            Object tenant = tenantType.getConstructor(String.class).newInstance("synthetic-local");
            Object registry = registryType.getMethod("singleTenant",tenantType,Map.class).invoke(null,tenant,Map.of("legacy-banking", policy()));
            Class<?> optionsType = isolated.loadClass(ReplayOptions.class.getName());
            Object config = optionsType.getConstructor(Duration.class, Duration.class, boolean.class)
                    .newInstance(Duration.ofSeconds(5), Duration.ofSeconds(25), true);
            Class<?> paramsType = isolated.loadClass(InvocationParameters.class.getName());
            Object params = paramsType.getConstructor(Map.class).newInstance(parameters().values());
            Class<?> engineType = isolated.loadClass(ReplayEngine.class.getName());
            Object engine = engineType.getConstructor(registryType, optionsType).newInstance(registry, config);
            Object result = engineType.getMethod("runValidation", String.class, paramsType).invoke(engine, fixture(), params);
            assertEquals("SUCCEEDED", result.getClass().getMethod("status").invoke(result).toString());
            assertEquals(0, modelLoads.get(), "Replay must not even request model classes");
        }
    }
}
