package com.surabhimarathe.interfaceautomation.discovery;

import com.microsoft.playwright.*;
import com.surabhimarathe.interfaceautomation.artifact.*;
import com.surabhimarathe.interfaceautomation.replay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import jakarta.servlet.Filter;
import java.math.BigDecimal;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.surabhimarathe.interfaceautomation.discovery.CompilationResult.Code.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DiscoveryCompilationTest.Pages.class)
class DiscoveryCompilationTest {
    @LocalServerPort int port;
    @TempDir Path temp;
    static volatile String replacement, replacementPath;
    static final AtomicInteger submissions = new AtomicInteger();
    @BeforeEach void reset() { replacement = null; replacementPath = null; submissions.set(0); }
    @AfterEach void clear() { reset(); }

    @TestConfiguration static class Pages {
        @Bean FilterRegistrationBean<Filter> captureTestPages() {
            var filter = new FilterRegistrationBean<Filter>();
            filter.setFilter((req, res, chain) -> {
                var http = (jakarta.servlet.http.HttpServletRequest) req;
                if (http.getRequestURI().endsWith("/submit")) submissions.incrementAndGet();
                if (replacement != null && http.getRequestURI().equals(replacementPath)) {
                    res.setContentType("text/html;charset=UTF-8");
                    res.getWriter().write(replacement); return;
                }
                chain.doFilter(req, res);
            });
            return filter;
        }
    }
    String origin() { return "http://localhost:" + port; }
    ReviewCheckpoint.Request request() { return ScriptedCompilationScenario.request(); }
    DecisionClient script() { return (g,o,t) -> ScriptedCompilationScenario.decide(o, request()); }
    record Finished(DiscoveryRunner.Result discovery, CompilationResult compilation, Path log, Path output) {}
    Finished run(DecisionClient client, int max, Duration timeout, ReviewCheckpoint.Request request, boolean persist) throws Exception {
        UUID id = UUID.randomUUID();
        Path log = temp.resolve("scripted-" + id + ".jsonl"), output = temp.resolve("artifacts-" + id);
        SafeEvents events = new SafeEvents(log,id);
        try (var session = new BrowserSession(ScriptedCompilationScenario.policy(origin()),true)) {
            session.enableCompilation(FeeReviewCapability.definition(),FeeReviewCapability.parameters(request),id,persist ? output : null);
            var result = new DiscoveryRunner(session,client,events,max,timeout).run(origin()+"/legacy",request);
            return new Finished(result,session.compilationResult(),log,output);
        }
    }
    Finished run(DecisionClient client, boolean persist) throws Exception { return run(client,20,Duration.ofSeconds(20),request(),persist); }
    void noArtifact(Finished f, CompilationResult.Code code) {
        assertEquals(code,f.compilation().code(),f.compilation().toString());
        assertNull(f.compilation().artifact());
        assertFalse(Files.exists(f.output()),"No artifact directory should be created for a rejected run");
    }

    @Test void successfulExecutionCompilesValidArtifactAndReplaysExactOutputsWithCorrelatedIdentity() throws Exception {
        Finished f = run(script(), false);
        assertEquals(ActionResult.Code.CHECKPOINT_VERIFIED, f.discovery().code());
        assertEquals(COMPILED, f.compilation().code(), f.compilation().toString());
        var artifact = f.compilation().artifact();
        new ArtifactValidator().validate(artifact);
        assertEquals(8,artifact.steps().size());
        assertEquals(8,f.compilation().successfulActions());
        assertEquals("Savings",artifact.steps().get(3).locator().context().text());
        assertEquals(ContextSpec.Kind.ROW,artifact.steps().get(3).locator().context().kind());
        assertEquals("Member Details",artifact.steps().get(2).postcondition().locator().accessibleName());
        assertEquals("${inputs.amount}",artifact.steps().get(5).inputExpression());
        assertEquals(f.discovery().runId(),artifact.provenance().traceId());
        assertEquals(f.compilation().runId(),artifact.provenance().traceId());
        assertTrue(f.log().getFileName().toString().contains(artifact.provenance().traceId().toString()));
        String json = new ArtifactJson().write(artifact);
        var mapper = new tools.jackson.databind.ObjectMapper();
        for (String line : Files.readAllLines(f.log()))
            assertEquals(artifact.provenance().traceId().toString(),mapper.readTree(line).get("runId").asText());
        for (String secret : List.of("100042","Morgan Lee","SAV-2048","Courtesy adjustment","25.00","observationId","controlId","xpath","transcript")) {
            assertFalse(json.contains(secret),"Compiled artifact must exclude sensitive material");
            assertFalse(Files.readString(f.log()).contains(secret),"Test log must exclude sensitive material");
        }
        assertFalse(Files.exists(f.output()));
        var replay = new ReplayEngine(new TargetRegistry(Map.of("legacy-banking",ScriptedCompilationScenario.policy(origin()))),
                new ReplayOptions(Duration.ofSeconds(5),Duration.ofSeconds(25),true))
                .run(json,new InvocationParameters(FeeReviewCapability.parameters(request())));
        assertEquals(ReplayResult.Status.SUCCEEDED,replay.status(),replay.toString());
        assertTrue(Map.of("memberId","100042","memberName","Morgan Lee","accountId","SAV-2048",
                "currentBalance",new BigDecimal("1842.73"),"amount",new BigDecimal("25.00"),
                "reason","Courtesy adjustment","projectedBalance",new BigDecimal("1867.73")).equals(replay.outputs()),
                "Seven exact typed outputs must match");
    }

    private static final String DENIED_REQUEST = "const xhr = new XMLHttpRequest();"
            + "xhr.open('POST','/legacy/accounts/SAV-2048/fee-reversal/submit',false);"
            + "try { xhr.send(); } catch (ignored) {}";
    private static final String SEARCH_PAGE = "<h1>Member Search</h1>"
            + "<form action='/legacy/members/search' method='post' onsubmit='return false'>"
            + "<label>Member ID<input name='memberId'></label>";

    @Test void configuredClickWithInterceptedBackgroundSubmitIsNotRecordedOrCompiled() throws Exception {
        replacementPath = "/legacy";
        replacement = SEARCH_PAGE + "<button onclick=\"fetch('/legacy/accounts/SAV-2048/fee-reversal/submit',"
                + "{method:'POST'}).catch(() => {})\">Search</button>";
        Finished f = run(script(),true);
        assertEquals(RunState.BLOCKED,f.discovery().state());
        assertEquals(ActionResult.Code.POLICY_DENIED,f.discovery().code());
        assertEquals(1,f.compilation().successfulActions(),"Only the preceding fill is recorded");
        noArtifact(f,DISCOVERY_NOT_VERIFIED);
        assertEquals(0,submissions.get());
    }

    @Test void deniedEntryRequestStopsBeforeDecisionsAndEmitsNothing() throws Exception {
        replacementPath = "/legacy";
        replacement = SEARCH_PAGE + "<script>" + DENIED_REQUEST + "</script>";
        AtomicInteger decisions = new AtomicInteger();
        Finished f = run((g,o,t) -> { decisions.incrementAndGet(); return ScriptedCompilationScenario.decide(o,request()); },true);
        assertEquals(RunState.BLOCKED,f.discovery().state());
        assertEquals(ActionResult.Code.POLICY_DENIED,f.discovery().code());
        assertEquals(0,decisions.get());
        assertEquals(0,f.compilation().successfulActions());
        assertNull(f.compilation().artifact());
        assertFalse(Files.exists(f.output()));
        assertEquals(0,submissions.get());
    }

    @Test void denialTriggeredWhileReadingObservationEmitsNothing() throws Exception {
        replacementPath = "/legacy";
        // The request starts only when observation evaluates the field label, not during navigation.
        replacement = SEARCH_PAGE + "<script>Object.defineProperty(document.querySelector('label'),'innerText',"
                + "{get() { " + DENIED_REQUEST + " return 'Member ID'; }});</script>";
        AtomicInteger decisions = new AtomicInteger();
        Finished f = run((g,o,t) -> { decisions.incrementAndGet(); return ScriptedCompilationScenario.decide(o,request()); },true);
        assertEquals(RunState.BLOCKED,f.discovery().state());
        assertEquals(ActionResult.Code.POLICY_DENIED,f.discovery().code());
        assertEquals(0,decisions.get());
        assertEquals(0,f.compilation().successfulActions());
        noArtifact(f,DISCOVERY_NOT_VERIFIED);
        assertEquals(0,submissions.get());
    }

    @Test void interceptedDenialRemainsStickyForObservationAndSubsequentAction() throws Exception {
        replacementPath = "/legacy";
        replacement = SEARCH_PAGE + "<button onclick=\"" + DENIED_REQUEST + "\">Search</button>";
        try (var session = new BrowserSession(ScriptedCompilationScenario.policy(origin()),true)) {
            var observed = session.open(origin()+"/legacy");
            var action = ScriptedCompilationScenario.click(observed,"Search");
            assertEquals(ActionResult.Code.POLICY_DENIED,session.execute(action).code());
            assertThrows(PolicyDeniedException.class,session::observe);
            assertEquals(ActionResult.Code.POLICY_DENIED,session.execute(action).code());
            assertEquals(0,submissions.get());
        }
    }

    @Test void waitPermanentlyDisqualifiesAnOtherwiseVerifiedEightActionRun() throws Exception {
        AtomicBoolean waited = new AtomicBoolean();
        Finished f = run((g,o,t) -> !waited.getAndSet(true)
                ? new UiAction(o.id(),UiAction.Type.WAIT,"",null)
                : ScriptedCompilationScenario.decide(o,request()),true);
        assertEquals(ActionResult.Code.CHECKPOINT_VERIFIED,f.discovery().code());
        assertEquals(8,f.compilation().successfulActions(),"Neither WAIT nor COMPLETE is a durable step");
        noArtifact(f,TRACE_NOT_REPLAYABLE);
    }

    @Test void exactInputCellIsParameterizedWhenItIsNeededForUniqueness() throws Exception {
        replacementPath = "/legacy/members/search";
        replacement = "<h1>Search Results</h1><table>"
                + row("100042","Morgan Lee","/legacy/members/100042")
                + row("999999","Other Person","/legacy/members/100042") + "</table>";
        Finished f = run(script(),false);
        assertEquals(COMPILED,f.compilation().code(),f.compilation().toString());
        assertEquals("${inputs.memberId}",f.compilation().artifact().steps().get(2).locator().context().text());
    }
    static String row(String id, String text, String destination) {
        return "<tr><td>"+id+"</td><td>"+text+"</td><td><a href='"+destination+"'>Open</a></td></tr>";
    }

    @Test void ambiguousAndPiiOnlyRowContextsFailClosedDespiteSuccessfulDiscovery() throws Exception {
        replacementPath = "/legacy/members/100042";
        for (String rows : List.of(
                row("SAV-2048","Savings","/legacy/accounts/SAV-2048")+row("SAV-9999","Savings","/legacy/accounts/SAV-2048"),
                row("SAV-2048","Morgan Lee","/legacy/accounts/SAV-2048")+row("SAV-9999","Other Person","/legacy/accounts/SAV-2048"))) {
            replacement = "<h1>Member Details</h1><table>"+rows+"</table>";
            Finished f = run(script(),true);
            assertEquals(ActionResult.Code.CHECKPOINT_VERIFIED,f.discovery().code());
            noArtifact(f,SEMANTIC_CAPTURE_FAILED);
        }
    }

    @Test void nonUniqueResultingHeadingIsNotTrustedAsAPostcondition() throws Exception {
        replacementPath = "/legacy/members/100042";
        replacement = "<h1>Member Details</h1><h1>Member Details</h1><table>"
                +row("SAV-2048","Savings","/legacy/accounts/SAV-2048")+"</table>";
        Finished f = run(script(),true);
        assertEquals(ActionResult.Code.CHECKPOINT_VERIFIED,f.discovery().code());
        noArtifact(f,SEMANTIC_CAPTURE_FAILED);
    }

    @Test void partialBlockedExpectedOutcomeAndTimeoutRunsNeverEmitArtifacts() throws Exception {
        Finished partial = run(script(),2,Duration.ofSeconds(20),request(),true);
        noArtifact(partial,DISCOVERY_NOT_VERIFIED);
        assertEquals(2,partial.compilation().successfulActions());
        Finished blocked = run((g,o,t) -> o.heading().equals("Fee Reversal Review")
                ? ScriptedCompilationScenario.click(o,"Submit reversal") : ScriptedCompilationScenario.decide(o,request()),true);
        noArtifact(blocked,DISCOVERY_NOT_VERIFIED);
        assertEquals(ActionResult.Code.POLICY_DENIED,blocked.discovery().code());
        assertEquals(8,blocked.compilation().successfulActions(),"Rejected click must not be recorded");
        var missing = new ReviewCheckpoint.Request("999999","SAV-2048",new BigDecimal("25.00"),"Courtesy adjustment");
        Finished expected = run((g,o,t) -> ScriptedCompilationScenario.decide(o,missing),20,Duration.ofSeconds(20),missing,true);
        noArtifact(expected,DISCOVERY_NOT_VERIFIED);
        assertEquals(ActionResult.Code.MEMBER_NOT_FOUND,expected.discovery().code());
        Finished timedOut = run((g,o,t) -> {
            try { Thread.sleep(150); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return ScriptedCompilationScenario.decide(o,request());
        },20,Duration.ofMillis(100),request(),true);
        noArtifact(timedOut,DISCOVERY_NOT_VERIFIED);
        assertEquals(RunState.TIMED_OUT,timedOut.discovery().state());
    }

    @Test void unboundSuccessfulFillPreventsCompilationEvenAfterVerifiedCompletion() throws Exception {
        AtomicInteger correction = new AtomicInteger();
        Finished f = run((g,o,t) -> {
            if (o.heading().equals("Prepare Fee Reversal") && ScriptedCompilationScenario.control(o,"Amount").filled()) {
                int attempt = correction.getAndIncrement();
                if (attempt < 2) return ScriptedCompilationScenario.fill(o,"Reason",attempt == 0 ? "Unrequested literal" : request().reason());
            }
            return ScriptedCompilationScenario.decide(o,request());
        },true);
        assertEquals(ActionResult.Code.CHECKPOINT_VERIFIED,f.discovery().code());
        noArtifact(f,COMPILATION_REJECTED);
    }

    @Test void resumedHandoffRunIsNotReplayableEvenWithoutManualTyping() throws Exception {
        UUID id = UUID.randomUUID();
        var events = new SafeEvents(temp.resolve("handoff-"+id+".jsonl"),id);
        Path output = temp.resolve("handoff-artifacts");
        var coordinator = new HandoffCoordinator(); coordinator.attach(events);
        AtomicBoolean requested = new AtomicBoolean();
        try (var session = new BrowserSession(ScriptedCompilationScenario.policy(origin()),true);
             var operator = Executors.newSingleThreadExecutor()) {
            session.enableCompilation(FeeReviewCapability.definition(),FeeReviewCapability.parameters(request()),id,output);
            var resumer = operator.submit(() -> {
                long until = System.nanoTime()+Duration.ofSeconds(10).toNanos();
                while (coordinator.status().owner() != HandoffCoordinator.Owner.HUMAN && System.nanoTime() < until)
                    Thread.sleep(10);
                return coordinator.resume(coordinator.resumeToken());
            });
            var result = new DiscoveryRunner(session,(g,o,t) -> !requested.getAndSet(true)
                    ? new UiAction(o.id(),UiAction.Type.REQUEST_HUMAN,"",null) : ScriptedCompilationScenario.decide(o,request()),
                    events,20,Duration.ofSeconds(20)).handoff(coordinator,Duration.ofSeconds(10),false,3).run(origin()+"/legacy",request());
            assertTrue(resumer.get(10,TimeUnit.SECONDS));
            assertEquals(ActionResult.Code.CHECKPOINT_VERIFIED,result.code());
            assertEquals(TRACE_NOT_REPLAYABLE,session.compilationResult().code());
            assertNull(session.compilationResult().artifact());
            assertFalse(Files.exists(output));
            coordinator.close();
        }
    }

    @Test void actualUnrecordedInputInSameBrowserDisqualifiesCompilation() throws Exception {
        int cdp;
        try (var socket = new ServerSocket(0)) { cdp = socket.getLocalPort(); }
        UUID id = UUID.randomUUID();
        var events = new SafeEvents(temp.resolve("manual-"+id+".jsonl"),id);
        Path output = temp.resolve("manual-artifacts");
        AtomicBoolean interacted = new AtomicBoolean();
        try (var session = new BrowserSession(ScriptedCompilationScenario.policy(origin()),true,
                List.of("--remote-debugging-port="+cdp))) {
            session.enableCompilation(FeeReviewCapability.definition(),FeeReviewCapability.parameters(request()),id,output);
            DecisionClient client = (g,o,t) -> {
                if (!interacted.getAndSet(true)) {
                    try (var playwright = Playwright.create(); var dispatcher = Executors.newSingleThreadExecutor()) {
                        var browser = playwright.chromium().connectOverCDP("http://localhost:"+cdp);
                        var page = browser.contexts().getFirst().pages().getFirst();
                        AtomicBoolean pumping = new AtomicBoolean(true);
                        var pump = dispatcher.submit(() -> { while (pumping.get()) session.pumpHumanEvents(); });
                        try { page.getByLabel("Member ID").fill("100042"); }
                        finally { pumping.set(false); pump.get(5,TimeUnit.SECONDS); }
                        session.pumpHumanEvents();
                    } catch (Exception ex) { throw new IllegalStateException("TEST_OPERATOR_FAILED"); }
                }
                return ScriptedCompilationScenario.decide(o,request());
            };
            var result = new DiscoveryRunner(session,client,events,20,Duration.ofSeconds(20)).run(origin()+"/legacy",request());
            assertEquals(ActionResult.Code.CHECKPOINT_VERIFIED,result.code());
            assertEquals(TRACE_NOT_REPLAYABLE,session.compilationResult().code());
            assertNull(session.compilationResult().artifact());
            assertFalse(Files.exists(output));
        }
    }

    @Test void integratedOutputCollisionIsStructuredAndPreservesOriginal() throws Exception {
        UUID id = UUID.randomUUID();
        Path output = temp.resolve("collision");
        Files.createDirectories(output);
        Path original = output.resolve("capability-"+id+".json");
        Files.writeString(original,"ORIGINAL",StandardOpenOption.CREATE_NEW);
        var events = new SafeEvents(temp.resolve("collision-"+id+".jsonl"),id);
        try (var session = new BrowserSession(ScriptedCompilationScenario.policy(origin()),true)) {
            session.enableCompilation(FeeReviewCapability.definition(),FeeReviewCapability.parameters(request()),id,output);
            var result = new DiscoveryRunner(session,script(),events,20,Duration.ofSeconds(20)).run(origin()+"/legacy",request());
            assertEquals(ActionResult.Code.CHECKPOINT_VERIFIED,result.code());
            assertEquals(OUTPUT_COLLISION,session.compilationResult().code());
            assertNull(session.compilationResult().artifact());
        }
        assertEquals("ORIGINAL",Files.readString(original));
        try (var files = Files.list(output)) { assertEquals(1,files.count()); }
    }

    @Test void failedDecisionRunEmitsNothingAndNeedsNoProviderExceptionClass() throws Exception {
        Finished failed = run((g,o,t) -> { throw new DecisionFailure("MODEL_FAILURE"); },true);
        noArtifact(failed,DISCOVERY_NOT_VERIFIED);
        assertEquals(RunState.FAILED,failed.discovery().state());
        assertEquals(0,failed.compilation().successfulActions());
    }

    @Test void compilationAndReplayWorkWithAllOpenRouterClassesUnavailable() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        URL main = BrowserSession.class.getProtectionDomain().getCodeSource().getLocation();
        URL tests = ScriptedCompilationScenario.class.getProtectionDomain().getCodeSource().getLocation();
        try (var isolated = new URLClassLoader(new URL[]{main,tests},getClass().getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.contains("OpenRouterClient")) { loads.incrementAndGet(); throw new ClassNotFoundException("MODEL_UNAVAILABLE"); }
                if (name.startsWith("com.surabhimarathe.interfaceautomation.")) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
                return super.loadClass(name,resolve);
            }
        }) {
            var harness = isolated.loadClass(ScriptedCompilationScenario.class.getName());
            Object result = harness.getMethod("isolatedRun",String.class,Path.class).invoke(null,origin(),temp);
            assertEquals(Map.of("compile","COMPILED","replay","SUCCEEDED"),result);
            assertEquals(0,loads.get());
        }
    }

    @Test void declarationsContainNoStepsAndCannotBeMistakenForAnExecutableArtifact() {
        var definition = FeeReviewCapability.definition();
        assertTrue(definition.draft().steps().isEmpty());
        new ArtifactValidator().validateDefinition(definition.draft());
        assertThrows(ArtifactValidationException.class, () -> new ArtifactValidator().validate(definition.draft()));
    }

    @Test void missingRunFilenameCorrelationDisqualifiesTheTrace() throws Exception {
        UUID id = UUID.randomUUID();
        var events = new SafeEvents(temp.resolve("uncorrelated.jsonl"),id);
        Path output = temp.resolve("uncorrelated-output");
        try (var session = new BrowserSession(ScriptedCompilationScenario.policy(origin()),true)) {
            session.enableCompilation(FeeReviewCapability.definition(),FeeReviewCapability.parameters(request()),id,output);
            var result = new DiscoveryRunner(session,script(),events,20,Duration.ofSeconds(20)).run(origin()+"/legacy",request());
            assertEquals(ActionResult.Code.CHECKPOINT_VERIFIED,result.code());
            assertEquals(TRACE_NOT_REPLAYABLE,session.compilationResult().code());
            assertNull(session.compilationResult().artifact());
            assertFalse(Files.exists(output));
        }
    }

    @Test void optInPublishesOnceAndCollisionCannotOverwrite() throws Exception {
        Finished f = run(script(),true);
        assertEquals(PERSISTED,f.compilation().code(),f.compilation().toString());
        Path file = f.output().resolve("capability-"+f.discovery().runId()+".json");
        byte[] original = Files.readAllBytes(file);
        assertThrows(FileAlreadyExistsException.class, () -> new ArtifactStore(f.output()).persist(f.compilation().artifact()));
        assertArrayEquals(original,Files.readAllBytes(file));
        try (var contents = Files.list(f.output())) { assertEquals(1,contents.count()); }
        new ArtifactJson().read(Files.readString(file));
    }
}
