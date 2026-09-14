package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.artifact.*;
import com.surabhimarathe.interfaceautomation.discovery.*;
import org.junit.jupiter.api.*;
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
    static final AtomicInteger submits = new AtomicInteger();
    static final AtomicInteger reviews = new AtomicInteger();
    static volatile long delayEntry;
    static volatile String entryHtml;
    final ObjectMapper mapper = new ObjectMapper();
    final ReplayOptions options = new ReplayOptions(Duration.ofSeconds(5), Duration.ofSeconds(25), true);
    @BeforeEach void reset() { submits.set(0); reviews.set(0); delayEntry = 0; entryHtml = null; }
    @AfterEach void resetDelay() { delayEntry = 0; entryHtml = null; }

    @TestConfiguration static class RequestCounts {
        @Bean FilterRegistrationBean<Filter> replayRequestCounts() {
            FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>();
            bean.setFilter((request, response, chain) -> {
                var http = (jakarta.servlet.http.HttpServletRequest) request;
                if (http.getRequestURI().endsWith("/submit")) submits.incrementAndGet();
                if (http.getRequestURI().endsWith("/review")) reviews.incrementAndGet();
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
    ReplayEngine engine() throws Exception { return new ReplayEngine(new TargetRegistry(Map.of("legacy-banking", policy())), options); }
    void failure(ReplayResult result, ReplayResult.Code code) {
        assertEquals(code == POLICY_DENIED ? BLOCKED : FAILED, result.status(), result.toString());
        assertEquals(code, result.code(), result.toString());
        assertTrue(result.outputs().isEmpty());
        assertNull(result.outcomeCode());
        for (String secret : List.of("100042", "Courtesy", "http", "Morgan", "1842"))
            assertFalse(result.toString().contains(secret));
    }

    @Test void fixtureReturnsSevenExactTypedOutputsWithoutSubmitting() throws Exception {
        ReplayResult result = engine().run(fixture(), parameters());
        assertEquals(SUCCEEDED, result.status(), result.toString());
        assertEquals(CHECKPOINT_VERIFIED, result.code());
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
        ReplayResult result = engine().run(artifact, parameters("999999", "Courtesy adjustment"));
        assertEquals(EXPECTED_OUTCOME, result.status(), result.toString());
        assertEquals(BUSINESS_OUTCOME, result.code());
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
        ReplayResult result = engine().run(fixture(), parameters("100042", " "));
        assertEquals(EXPECTED_OUTCOME, result.status(), result.toString());
        assertEquals("VALIDATION_REJECTED", result.outcomeCode());
        assertTrue(result.toString().contains("code=VALIDATION_REJECTED"));
        assertFalse(result.toString().contains("100042"));
        assertEquals(8, result.step());
        assertTrue(result.outputs().isEmpty());
    }

    @Test void missingRowContextMakesSecondOpenAmbiguous() throws Exception {
        String artifact = changed(n -> at(n, "/steps/3/locator").putNull("context"));
        ReplayResult result = engine().run(artifact, parameters());
        failure(result, AMBIGUOUS_LOCATOR);
        assertEquals(4, result.step());
    }

    @Test void missingLocatorAndWrongPostconditionHaveDistinctCodes() throws Exception {
        String missing = changed(n -> {
            at(n, "/steps/0/locator").put("accessibleName", "Absent field");
            at(n, "/steps/0/postcondition/locator").put("accessibleName", "Absent field");
        });
        failure(engine().run(missing, parameters()), ZERO_LOCATOR);
        String post = changed(n -> at(n, "/steps/1/postcondition/locator").put("accessibleName", "Absent heading"));
        failure(engine().run(post, parameters()), POSTCONDITION_FAILED);
    }

    @Test void checkpointAndExtractionMustBothVerify() throws Exception {
        failure(engine().run(changed(n -> at(n, "/checkpoint/marker").put("accessibleName", "Absent checkpoint")),
                parameters()), CHECKPOINT_FAILED);
        failure(engine().run(changed(n -> at(n, "/outputs/memberName/constraints").put("maxLength", 3)),
                parameters()), EXTRACTION_FAILED);
        failure(engine().run(changed(n -> at(n, "/checkpoint/extractors/0/locator").put("accessibleName", "Missing row")),
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
        ReplayEngine engine = new ReplayEngine(new TargetRegistry(Map.of("legacy-banking", broad)), options);
        ReplayResult result = engine.run(artifact, parameters());
        failure(result, POLICY_DENIED);
        assertEquals(9, result.step());
        assertEquals(0, submits.get());
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base.origin()
                + "/legacy/accounts/SAV-2048")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.body().contains("$1842.73"));
    }

    @Test void invalidArtifactParametersUnknownTargetAndEntryPolicyFailBeforeLaunch() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        ReplayEngine engine = new ReplayEngine(new TargetRegistry(Map.of("legacy-banking", policy())), options,
                (p, o) -> { launches.incrementAndGet(); throw new IllegalStateException("PRIVATE"); });
        failure(engine.run("{secret", parameters()), INVALID_ARTIFACT);
        for (Object amount : List.of("25.00", new BigDecimal("0"), new BigDecimal("100.01"), new BigDecimal("1.001"))) {
            var values = new HashMap<>(parameters().values());
            values.put("amount", amount);
            failure(engine.run(fixture(), new InvocationParameters(values)), INVALID_PARAMETERS);
        }
        var extra = new HashMap<>(parameters().values());
        extra.put("extra", "PRIVATE");
        failure(engine.run(fixture(), new InvocationParameters(extra)), INVALID_PARAMETERS);
        failure(engine.run(fixture(), new InvocationParameters(Map.of())), INVALID_PARAMETERS);
        failure(engine.run(changed(n -> at(n, "/target").put("targetId", "unknown")), parameters()), UNKNOWN_TARGET);
        failure(engine.run(changed(n -> at(n, "/target").put("origin", "http://evil.example")), parameters()), INVALID_ARTIFACT);
        failure(engine.run(changed(n -> at(n, "/target").put("entryPath", "/admin")), parameters()), POLICY_DENIED);
        assertEquals(0, launches.get());
    }

    @Test void browserExceptionsAreRedacted() throws Exception {
        var engine = new ReplayEngine(new TargetRegistry(Map.of("legacy-banking", policy())), options,
                (p,o) -> { throw new IllegalStateException("PRIVATE https://data/100042"); });
        failure(engine.run(fixture(), parameters()), BROWSER_FAILURE);
    }

    @Test void perStepAndOverallDeadlinesAreBounded() throws Exception {
        delayEntry = 1200;
        var registry = new TargetRegistry(Map.of("legacy-banking", policy()));
        var stepBound = new ReplayEngine(registry, new ReplayOptions(Duration.ofMillis(150), Duration.ofSeconds(10), true));
        failure(stepBound.run(fixture(), parameters()), TIMEOUT);
        long start = System.nanoTime();
        var overallBound = new ReplayEngine(registry, new ReplayOptions(Duration.ofSeconds(5), Duration.ofMillis(100), true));
        failure(overallBound.run(fixture(), parameters()), TIMEOUT);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
        assertEquals(0, reviews.get());
    }

    @Test void outcomeOnEntryPrecedesAnyStepLocator() throws Exception {
        entryHtml = "<main><div role='status' aria-label='Member not found'>No matching record.</div></main>";
        ReplayResult result = engine().run(fixture(), parameters());
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
        failure(engine().run(fixture(), parameters()), POLICY_DENIED);
        assertEquals(0, submits.get());
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
            ReplayResult result = engine().run(artifact, parameters());
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
        ReplayResult result = engine().run(artifact, parameters());
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
            Object registry = registryType.getConstructor(Map.class).newInstance(Map.of("legacy-banking", policy()));
            Class<?> optionsType = isolated.loadClass(ReplayOptions.class.getName());
            Object config = optionsType.getConstructor(Duration.class, Duration.class, boolean.class)
                    .newInstance(Duration.ofSeconds(5), Duration.ofSeconds(25), true);
            Class<?> paramsType = isolated.loadClass(InvocationParameters.class.getName());
            Object params = paramsType.getConstructor(Map.class).newInstance(parameters().values());
            Class<?> engineType = isolated.loadClass(ReplayEngine.class.getName());
            Object engine = engineType.getConstructor(registryType, optionsType).newInstance(registry, config);
            Object result = engineType.getMethod("run", String.class, paramsType).invoke(engine, fixture(), params);
            assertEquals("SUCCEEDED", result.getClass().getMethod("status").invoke(result).toString());
            assertEquals(0, modelLoads.get(), "Replay must not even request model classes");
        }
    }
}
