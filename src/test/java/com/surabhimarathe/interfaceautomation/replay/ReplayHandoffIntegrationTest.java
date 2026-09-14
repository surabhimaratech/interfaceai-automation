package com.surabhimarathe.interfaceautomation.replay;

import com.microsoft.playwright.*;
import com.surabhimarathe.interfaceautomation.artifact.*;
import com.surabhimarathe.interfaceautomation.discovery.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import jakarta.servlet.Filter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.surabhimarathe.interfaceautomation.replay.ReplayResult.Code.*;
import static com.surabhimarathe.interfaceautomation.discovery.HandoffCoordinator.Owner.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReplayHandoffIntegrationTest.Pages.class)
class ReplayHandoffIntegrationTest {
    @LocalServerPort int port;
    @TempDir Path temporary;
    static volatile String entry, replacement, replacementPath;
    static volatile long reviewDelay;
    static final AtomicInteger submits = new AtomicInteger(), reviews = new AtomicInteger();
    static final AtomicBoolean cookieSurvived = new AtomicBoolean();
    final ObjectMapper mapper = new ObjectMapper();
    static final String MARKER = "<div role='alert' aria-label='SESSION_EXPIRED'>PRIVATE_PAGE</div>";
    static final String FORM = "<main><h1>Member Search</h1><form method='post' action='/legacy/members/search'>"
            + "<label for='member'>Member ID</label><input id='member' name='memberId'>"
            + "<button>Search</button></form>";
    static final String REPAIR = "<label for='repair'>Operator recovery</label><input id='repair'>"
            + "<button type='button' onclick=\"document.querySelector('[role=alert]').remove();"
            + "const old=document.getElementById('member');old.replaceWith(old.cloneNode());"
            + "document.getElementById('repair').remove();this.remove()\">Restore session</button></main>";
    @BeforeEach void reset() {
        entry = FORM + MARKER + REPAIR; replacement = null; replacementPath = null;
        reviewDelay = 0; submits.set(0); reviews.set(0); cookieSurvived.set(false);
    }
    @AfterEach void clear() { entry = null; replacement = null; replacementPath = null; reviewDelay = 0; }
    @TestConfiguration static class Pages {
        @Bean FilterRegistrationBean<Filter> replayHandoffPages() {
            var registration = new FilterRegistrationBean<Filter>();
            registration.setFilter((req,res,chain) -> {
                var request = (jakarta.servlet.http.HttpServletRequest) req;
                var response = (jakarta.servlet.http.HttpServletResponse) res;
                String path = request.getRequestURI();
                if (path.endsWith("/submit")) submits.incrementAndGet();
                if (path.endsWith("/review")) {
                    reviews.incrementAndGet();
                    if (request.getCookies() != null)
                        for (var cookie : request.getCookies())
                            if (cookie.getName().equals("handoff_proof") && cookie.getValue().equals("PRIVATE_COOKIE")) cookieSurvived.set(true);
                    if (reviewDelay > 0) try { Thread.sleep(reviewDelay); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                }
                String html = path.equals("/legacy") ? entry : path.equals(replacementPath) ? replacement : null;
                if (html != null) {
                    response.setContentType("text/html;charset=UTF-8");
                    if (path.equals("/legacy")) response.addHeader("Set-Cookie","handoff_proof=PRIVATE_COOKIE; Path=/; HttpOnly; SameSite=Lax");
                    response.getWriter().write(html); return;
                }
                chain.doFilter(req,res);
            });
            return registration;
        }
    }
    String origin() { return "http://localhost:"+port; }
    String fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/artifacts/prepare-fee-reversal-review.v1.json")) {
            return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    InvocationParameters parameters() { return new InvocationParameters(FeeReviewCapability.parameters(ScriptedCompilationScenario.request())); }
    TargetRegistry registry(boolean configured) throws Exception {
        return TargetRegistry.singleTenant(new TenantId("synthetic-local"), Map.of("legacy-banking",ScriptedCompilationScenario.policy(origin())),
                configured ? Map.of("legacy-banking",new SessionExpiryMarker(LocatorSpec.Role.ALERT,"SESSION_EXPIRED")) : Map.of());
    }
    ReplayOptions options() { return new ReplayOptions(Duration.ofSeconds(3),Duration.ofSeconds(25),false); }
    void awaitHuman(ReplayHandoff handoff) throws Exception {
        long until = System.nanoTime()+Duration.ofSeconds(12).toNanos();
        while (handoff.status().owner() != HUMAN && System.nanoTime() < until) Thread.sleep(10);
        assertEquals(HUMAN,handoff.status().owner());
    }
    String operatorOrigin(ReplayOperatorServer server) { return "http://127.0.0.1:"+server.port(); }
    HttpResponse<String> get(ReplayOperatorServer server, String suffix) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(operatorOrigin(server)+"/replay-operator"+suffix)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
    String cookie(ReplayOperatorServer server) throws Exception {
        var response = get(server,"");
        assertEquals(200,response.statusCode());
        String header = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Strict"));
        return header.split(";")[0];
    }
    int resume(ReplayOperatorServer server, String cookie) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(operatorOrigin(server)+"/replay-operator/resume"))
                .header("Origin",operatorOrigin(server)).header("Cookie",cookie)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    int freePort() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    void assertFailure(ReplayResult result, ReplayResult.Code code) {
        assertEquals(code,result.code(),result.toString());
        assertTrue(result.outputs().isEmpty());
        assertNotNull(result.diagnostic());
        assertEquals(code,result.diagnostic().code());
        assertEquals(0,submits.get());
    }

    @Test void humanRepairsSameHeadedPageAndFreshHandleCompletesAllSevenOutputs() throws Exception {
        int cdp = freePort();
        var launches = new AtomicInteger(); var contexts = new AtomicInteger(); var pages = new AtomicInteger();
        var identity = new AtomicBoolean();
        var browserRef = new AtomicReference<Browser>();
        var contextRef = new AtomicReference<BrowserContext>();
        var pageRef = new AtomicReference<Page>();
        try (var handoff = new ReplayHandoff(Duration.ofSeconds(12),2);
             var operator = new ReplayOperatorServer(handoff,0);
             var executor = Executors.newSingleThreadExecutor()) {
            var engine = new ReplayEngine(registry(true),options(),(p,o) -> {
                launches.incrementAndGet();
                var browser = p.chromium().launch(o.setArgs(List.of("--remote-debugging-port="+cdp)));
                browserRef.set(browser);
                browser.onContext(context -> {
                    contexts.incrementAndGet(); contextRef.set(context);
                    context.addCookies(List.of(new com.microsoft.playwright.options.Cookie("handoff_proof","PRIVATE_COOKIE").setUrl(origin())));
                    context.onPage(page -> {
                        pages.incrementAndGet(); pageRef.set(page);
                        page.onRequest(req -> {
                            if (req.url().endsWith("/review"))
                                identity.set(browserRef.get() == browser && contextRef.get() == context && pageRef.get() == page
                                        && page.context() == context && context.browser() == browser);
                        });
                    });
                });
                return browser;
            },null,handoff);
            String json = fixture();
            var pending = executor.submit(() -> engine.run(json,parameters()));
            awaitHuman(handoff);
            assertEquals(1,handoff.status().step());
            assertThrows(IllegalStateException.class,handoff::requireAutomation);
            try (var playwright = Playwright.create()) {
                var attached = playwright.chromium().connectOverCDP("http://localhost:"+cdp);
                var context = attached.contexts().getFirst();
                var page = context.pages().getFirst();
                assertEquals(1,context.pages().size());
                var stale = page.getByLabel("Member ID").elementHandle();
                assertTrue(page.getByLabel("Member ID").inputValue().isEmpty(),"Automation must not fill while HUMAN owns the page");
                page.getByLabel("Operator recovery").fill("PRIVATE_OPERATOR_VALUE");
                assertEquals(0,reviews.get());
                assertEquals(HUMAN,handoff.status().owner());
                page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Restore session").setExact(true)).click();
                assertEquals(Boolean.FALSE,stale.evaluate("e => e.isConnected"),"The pre-handoff target must be detached");
                stale.dispose();
                String signal = cookie(operator);
                assertEquals(303,resume(operator,signal));
                assertEquals(409,resume(operator,signal));
                var result = pending.get(15,TimeUnit.SECONDS);
                assertEquals(ReplayResult.Status.SUCCEEDED,result.status(),result.toString());
                assertEquals(CHECKPOINT_VERIFIED,result.code());
                assertEquals(8,result.step());
                assertTrue(Map.of("memberId","100042","memberName","Morgan Lee","accountId","SAV-2048",
                        "currentBalance",new java.math.BigDecimal("1842.73"),"amount",new java.math.BigDecimal("25.00"),
                        "reason","Courtesy adjustment","projectedBalance",new java.math.BigDecimal("1867.73")).equals(result.outputs()));
                assertNull(result.diagnostic());
            }
            assertEquals(1,launches.get()); assertEquals(1,contexts.get()); assertEquals(1,pages.get());
            assertTrue(identity.get()); assertTrue(cookieSurvived.get());
            assertEquals(1,reviews.get()); assertEquals(0,submits.get());
        }
    }

    @Test void configuredMarkerWithoutCoordinatorReturnsHumanRequiredAndPersistsOnlyDiagnostic() throws Exception {
        Path directory = temporary.resolve("diagnostics");
        var result = new ReplayEngine(registry(true),options(),new DiagnosticStore(directory)).run(fixture(),parameters());
        assertFailure(result,HUMAN_ACTION_REQUIRED);
        assertEquals(ReplayResult.Disposition.RECOVERABLE,result.disposition());
        assertEquals(ReplayResult.DiagnosticPersistence.STORED,result.diagnosticPersistence());
        assertTrue(result.diagnostic().conditions().sessionExpiryDetected());
        assertTrue(result.diagnostic().conditions().humanActionRequired());
        assertFalse(result.diagnostic().conditions().actionStarted());
        assertEquals(0,result.diagnostic().conditions().handoffCount());
        try (var files = Files.list(directory)) {
            var paths = files.toList(); assertEquals(1,paths.size());
            String json = Files.readString(paths.getFirst());
            for (String value : List.of("PRIVATE_PAGE","PRIVATE_COOKIE","100042","Courtesy adjustment","http","SESSION_EXPIRED","token","selector"))
                assertFalse(json.contains(value),"Sensitive data escaped diagnostic boundary");
        }
    }

    @Test void unconfiguredAndNonSemanticExpiryTextNeverTriggersHandoff() throws Exception {
        try (var handoff = new ReplayHandoff(Duration.ofSeconds(1),1)) {
            var result = new ReplayEngine(registry(false),options(),null,handoff).run(fixture(),parameters());
            assertEquals(CHECKPOINT_VERIFIED,result.code(),result.toString());
            assertEquals(0,handoff.status().epoch());
        }
        entry = FORM + "<p>SESSION_EXPIRED PRIVATE_PAGE</p></main>";
        try (var handoff = new ReplayHandoff(Duration.ofSeconds(1),1)) {
            assertEquals(CHECKPOINT_VERIFIED,new ReplayEngine(registry(true),options(),null,handoff).run(fixture(),parameters()).code());
            assertEquals(0,handoff.status().epoch());
        }
    }

    @Test void handoffTimeoutIsDistinctAndInvalidatesLateResume() throws Exception {
        try (var handoff = new ReplayHandoff(Duration.ofMillis(600),2);
             var operator = new ReplayOperatorServer(handoff,0);
             var executor = Executors.newSingleThreadExecutor()) {
            String json = fixture();
            var pending = executor.submit(() -> new ReplayEngine(registry(true),options(),null,handoff).run(json,parameters()));
            awaitHuman(handoff);
            String signal = cookie(operator);
            var result = pending.get(10,TimeUnit.SECONDS);
            assertFailure(result,HANDOFF_TIMEOUT);
            assertEquals(ReplayResult.Disposition.HARD_FAILURE,result.disposition());
            assertTrue(result.diagnostic().conditions().handoffTimedOut());
            assertEquals(1,result.diagnostic().conditions().handoffCount());
            assertEquals(409,resume(operator,signal));
            assertEquals(CLOSED,handoff.status().owner());
        }
    }

    @Test void unchangedExpiryAfterResumeHitsLimitWithoutAnyActionRetry() throws Exception {
        try (var handoff = new ReplayHandoff(Duration.ofSeconds(4),1);
             var operator = new ReplayOperatorServer(handoff,0);
             var executor = Executors.newSingleThreadExecutor()) {
            String json = fixture();
            var pending = executor.submit(() -> new ReplayEngine(registry(true),options(),null,handoff).run(json,parameters()));
            awaitHuman(handoff);
            assertEquals(303,resume(operator,cookie(operator))); // No repair: marker still present.
            var result = pending.get(10,TimeUnit.SECONDS);
            assertFailure(result,HANDOFF_LIMIT);
            assertTrue(result.diagnostic().conditions().handoffLimitReached());
            assertFalse(result.diagnostic().conditions().actionStarted());
            assertEquals(1,result.diagnostic().conditions().handoffCount());
            assertEquals(1,result.step());
            assertEquals(0,reviews.get());
        }
    }

    @Test void dialogsPopupsAndDeniedRequestsCannotEnterHandoff() throws Exception {
        for (String script : List.of("alert('PRIVATE_DIALOG')", "window.open('about:blank')",
                "const x=new XMLHttpRequest();x.open('POST','/legacy/accounts/SAV-2048/fee-reversal/submit',false);try{x.send()}catch(e){}")) {
            entry = FORM + MARKER + "<script>"+script+"</script></main>";
            try (var handoff = new ReplayHandoff(Duration.ofSeconds(1),1)) {
                var result = new ReplayEngine(registry(true),options(),null,handoff).run(fixture(),parameters());
                assertTrue(Set.of(UNEXPECTED_DIALOG,BROWSER_FAILURE,POLICY_DENIED).contains(result.code()),result.toString());
                assertEquals(0,handoff.status().epoch());
                assertEquals(0,result.diagnostic().conditions().handoffCount());
                assertEquals(0,submits.get());
            }
        }
    }

    @Test void configuredSubmitDestinationIsBlockedEvenWithExpiryMarker() throws Exception {
        entry = FORM.replace("/legacy/members/search","/legacy/accounts/SAV-2048/fee-reversal/submit") + MARKER + "</main>";
        ObjectNode artifact = (ObjectNode) mapper.readTree(fixture());
        var step = artifact.at("/steps/1").deepCopy();
        artifact.putArray("steps").add(step);
        try (var handoff = new ReplayHandoff(Duration.ofSeconds(1),1)) {
            var result = new ReplayEngine(registry(true),options(),null,handoff).run(artifact.toString(),parameters());
            assertFailure(result,POLICY_DENIED);
            assertEquals(0,handoff.status().epoch());
            assertFalse(result.diagnostic().conditions().actionStarted());
        }
    }

    @Test void postDispatchTimeoutNeverHandsOffOrRetriesReview() throws Exception {
        entry = null;
        reviewDelay = 1800;
        try (var handoff = new ReplayHandoff(Duration.ofSeconds(2),1)) {
            var bounded = new ReplayOptions(Duration.ofMillis(700),Duration.ofSeconds(20),false);
            var result = new ReplayEngine(registry(true),bounded,null,handoff).run(fixture(),parameters());
            assertFailure(result,TIMEOUT);
            assertTrue(result.diagnostic().conditions().actionStarted());
            assertEquals(8,result.step());
            assertEquals(0,handoff.status().epoch());
            assertEquals(1,reviews.get());
        }
    }

    @Test void overallDeadlineWhileHumanOwnsSessionReturnsHandoffTimeout() throws Exception {
        try (var handoff = new ReplayHandoff(Duration.ofSeconds(10),1)) {
            var bounded = new ReplayOptions(Duration.ofSeconds(2),Duration.ofSeconds(4),false);
            var result = new ReplayEngine(registry(true),bounded,null,handoff).run(fixture(),parameters());
            assertFailure(result,HANDOFF_TIMEOUT);
            assertTrue(result.diagnostic().conditions().handoffTimedOut());
            assertFalse(result.diagnostic().conditions().actionStarted());
            assertEquals(CLOSED,handoff.status().owner());
        }
    }

    @Test void resumeRechecksActualUrlAndRedactsHumanInput() throws Exception {
        int cdp = freePort();
        try (var handoff = new ReplayHandoff(Duration.ofSeconds(10),1);
             var operator = new ReplayOperatorServer(handoff,0);
             var executor = Executors.newSingleThreadExecutor()) {
            var engine = new ReplayEngine(registry(true),options(),
                    (p,o) -> p.chromium().launch(o.setArgs(List.of("--remote-debugging-port="+cdp))),null,handoff);
            String artifact = fixture();
            var pending = executor.submit(() -> engine.run(artifact,parameters()));
            awaitHuman(handoff);
            String signal = cookie(operator);
            try (var playwright = Playwright.create()) {
                var attached = playwright.chromium().connectOverCDP("http://localhost:"+cdp);
                var page = attached.contexts().getFirst().pages().getFirst();
                page.getByLabel("Operator recovery").fill("PRIVATE_OPERATOR_VALUE");
                page.evaluate("() => history.replaceState(null,'','/private-human-state')");
                assertEquals(303,resume(operator,signal));
                var result = pending.get(10,TimeUnit.SECONDS);
                assertFailure(result,POLICY_DENIED);
                assertEquals(ReplayDiagnostic.Phase.RESUMING,result.diagnostic().phase());
                assertFalse(result.diagnostic().conditions().actionStarted());
                String diagnostic = mapper.writeValueAsString(result.diagnostic());
                for (String secret : List.of("PRIVATE_OPERATOR_VALUE","private-human-state","http","100042","PRIVATE_COOKIE",
                        signal.substring(signal.indexOf('=')+1))) {
                    assertFalse(diagnostic.contains(secret),"Sensitive input must not appear in diagnostics");
                    assertFalse(result.toString().contains(secret),"Sensitive input must not appear in the result");
                }
                assertEquals(0,reviews.get());
            }
        }
    }

    @Test void duplicateSemanticMarkersFailClosedRatherThanHandingOff() throws Exception {
        entry = FORM + MARKER + MARKER + "</main>";
        try (var handoff = new ReplayHandoff(Duration.ofSeconds(1),1)) {
            var result = new ReplayEngine(registry(true),options(),null,handoff).run(fixture(),parameters());
            assertFailure(result,AMBIGUOUS_LOCATOR);
            assertEquals(0,handoff.status().epoch());
        }
    }
}
