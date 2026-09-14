package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.InterfaceaiAutomationApplication;
import com.surabhimarathe.interfaceautomation.artifact.*;
import com.surabhimarathe.interfaceautomation.discovery.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import jakarta.servlet.Filter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.surabhimarathe.interfaceautomation.replay.ReplayResult.Code.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantReplayIntegrationTest {
    static final Map<Integer,Map<String,String>> pages = new ConcurrentHashMap<>();
    static final Map<Integer,AtomicInteger> requests = new ConcurrentHashMap<>();
    ConfigurableApplicationContext first, second;
    int portA, portB;
    final TenantId a = new TenantId("labTenantA"), b = new TenantId("labTenantB");
    final ObjectMapper mapper = new ObjectMapper();
    final ReplayOptions options = new ReplayOptions(Duration.ofSeconds(4),Duration.ofSeconds(25),true);
    @TempDir Path temporary;
    @BeforeAll void start() {
        first = startServer();
        try { second = startServer(); }
        catch (RuntimeException ex) { first.close(); throw ex; }
        portA = ((WebServerApplicationContext)first).getWebServer().getPort();
        portB = ((WebServerApplicationContext)second).getWebServer().getPort();
    }
    ConfigurableApplicationContext startServer() {
        return new SpringApplicationBuilder(InterfaceaiAutomationApplication.class,Pages.class)
                .run("--server.port=0","--discovery.flow=false","--discovery.proof=false","--spring.main.banner-mode=off");
    }
    @AfterAll void stop() { if (second != null) second.close(); if (first != null) first.close(); }
    @BeforeEach void reset() { pages.clear(); requests.clear(); }
    @TestConfiguration static class Pages {
        @Bean FilterRegistrationBean<Filter> tenantTestPages() {
            var bean = new FilterRegistrationBean<Filter>();
            bean.setFilter((req,res,chain) -> {
                var request = (jakarta.servlet.http.HttpServletRequest)req;
                requests.computeIfAbsent(request.getLocalPort(),p -> new AtomicInteger()).incrementAndGet();
                String replacement = pages.getOrDefault(request.getLocalPort(),Map.of()).get(request.getRequestURI());
                if (replacement != null) {
                    res.setContentType("text/html;charset=UTF-8"); res.getWriter().write(replacement); return;
                }
                chain.doFilter(req,res);
            });
            return bean;
        }
    }
    String origin(int port) { return "http://localhost:"+port; }
    ActionPolicy policy(int port) throws Exception { return ScriptedCompilationScenario.policy(origin(port)); }
    TenantTargetConfiguration config(int port, SessionExpiryMarker marker, DiagnosticRedactionPolicy redaction) throws Exception {
        return new TenantTargetConfiguration(policy(port),marker,redaction);
    }
    TenantTargetRegistry registry(DiagnosticRedactionPolicy redaction) throws Exception {
        return new TenantTargetRegistry(Map.of(
                a,Map.of("legacy-banking",config(portA,null,redaction)),
                b,Map.of("legacy-banking",config(portB,null,DiagnosticRedactionPolicy.baseline()))));
    }
    String fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/artifacts/prepare-fee-reversal-review.v1.json")) {
            return new String(Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    InvocationParameters parameters() { return new InvocationParameters(FeeReviewCapability.parameters(ScriptedCompilationScenario.request())); }
    void exact(ReplayResult result) {
        assertEquals(CHECKPOINT_VERIFIED,result.code(),result.toString());
        assertEquals(ReplayResult.Disposition.SUCCESS,result.disposition());
        assertTrue(Map.of("memberId","100042","memberName","Morgan Lee","accountId","SAV-2048",
                "currentBalance",new BigDecimal("1842.73"),"amount",new BigDecimal("25.00"),
                "reason","Courtesy adjustment","projectedBalance",new BigDecimal("1867.73")).equals(result.outputs()),
                "Seven typed outputs must match without diagnostic redaction affecting execution");
    }
    int count(int port) { return requests.getOrDefault(port,new AtomicInteger()).get(); }
    ObjectNode artifact() throws Exception { return (ObjectNode)mapper.readTree(fixture()); }
    String entry(String action, String marker) {
        return "<h1>Member Search</h1><form method='post' action='"+action+"'>"
                + "<label>Member ID<input name='memberId'></label><button>Search</button></form>"+marker;
    }
    void failure(ReplayResult result, ReplayResult.Code code) throws Exception {
        assertEquals(code,result.code(),result.toString());
        assertTrue(result.outputs().isEmpty());
        assertNotNull(result.diagnostic());
        String diagnostic = mapper.writeValueAsString(result.diagnostic());
        for (String sensitive : List.of(a.value(),b.value(),"100042","Morgan Lee","SAV-2048","Courtesy adjustment",
                "PRIVATE_COOKIE","PRIVATE_TOKEN","PRIVATE_EXCEPTION","http","selector","<html")) {
            assertFalse(diagnostic.contains(sensitive),"Diagnostic must not expose sensitive data");
            assertFalse(result.toString().contains(sensitive),"Result must not expose sensitive data");
        }
    }

    @Test void sameArtifactSucceedsAtTwoIsolatedOrigins() throws Exception {
        assertNotEquals(portA,portB);
        var engine = new ReplayEngine(registry(DiagnosticRedactionPolicy.baseline()),options);
        String json = fixture();
        exact(engine.run(a,json,parameters()));
        assertTrue(count(portA)>0); assertEquals(0,count(portB));
        int prior = count(portA);
        exact(engine.run(b,json,parameters()));
        assertEquals(prior,count(portA)); assertTrue(count(portB)>0);
    }

    @Test void controlPermissionsAreTenantSpecificWithoutFallback() throws Exception {
        var base = policy(portB);
        var names = new HashMap<>(base.controlNames());
        var clicks = new HashSet<>(names.get(UiAction.Type.CLICK)); clicks.remove("Search");
        names.put(UiAction.Type.CLICK,clicks);
        var restricted = new ActionPolicy(base.origin(),base.routes(),base.actions(),names);
        var registry = new TenantTargetRegistry(Map.of(
                a,Map.of("legacy-banking",config(portA,null,DiagnosticRedactionPolicy.baseline())),
                b,Map.of("legacy-banking",new TenantTargetConfiguration(restricted,null,DiagnosticRedactionPolicy.baseline()))));
        var engine = new ReplayEngine(registry,options);
        exact(engine.run(a,fixture(),parameters()));
        var denied = engine.run(b,fixture(),parameters());
        failure(denied,POLICY_DENIED); assertEquals(2,denied.step());
    }

    @Test void onlyTheSelectedTenantsSemanticMarkerCanRequestHumanAction() throws Exception {
        var registry = new TenantTargetRegistry(Map.of(
                a,Map.of("legacy-banking",config(portA,new SessionExpiryMarker(LocatorSpec.Role.ALERT,"Expiry A"),DiagnosticRedactionPolicy.baseline())),
                b,Map.of("legacy-banking",config(portB,new SessionExpiryMarker(LocatorSpec.Role.STATUS,"Expiry B"),DiagnosticRedactionPolicy.baseline()))));
        var engine = new ReplayEngine(registry,options);
        pages.put(portA,Map.of("/legacy",entry("/legacy/members/search","<div role='alert' aria-label='Expiry A'>PRIVATE_PAGE</div>")));
        pages.put(portB,Map.of("/legacy",entry("/legacy/members/search","<div role='status' aria-label='Expiry B'>PRIVATE_PAGE</div>")));
        failure(engine.run(a,fixture(),parameters()),HUMAN_ACTION_REQUIRED);
        failure(engine.run(b,fixture(),parameters()),HUMAN_ACTION_REQUIRED);
        pages.put(portA,pages.get(portB)); // Wrong tenant's marker is not an expiry signal.
        exact(engine.run(a,fixture(),parameters()));
    }

    @Test void crossOriginControlAndBackgroundRequestAreBothDeniedWithoutContactingOtherTenant() throws Exception {
        var engine = new ReplayEngine(registry(DiagnosticRedactionPolicy.baseline()),options);
        pages.put(portA,Map.of("/legacy",entry(origin(portB)+"/legacy/members/search","")));
        failure(engine.run(a,fixture(),parameters()),POLICY_DENIED);
        assertEquals(0,count(portB));
        pages.put(portA,Map.of("/legacy",entry("/legacy/members/search",
                "<script>const x=new XMLHttpRequest();x.open('POST','"+origin(portB)+"/legacy/members/search',false);try{x.send()}catch(e){}</script>")));
        failure(engine.run(a,fixture(),parameters()),POLICY_DENIED);
        assertEquals(0,count(portB));
    }

    @Test void unknownScopesAndArtifactOverridesFailBeforeBrowserLaunchAndPersistSafely() throws Exception {
        var registry = new TenantTargetRegistry(Map.of(
                a,Map.of("legacy-banking",config(portA,null,DiagnosticRedactionPolicy.baseline())),
                b,Map.of("only-in-b",config(portB,null,DiagnosticRedactionPolicy.baseline()))));
        var launches = new AtomicInteger();
        Path directory = temporary.resolve("diagnostics");
        var engine = new ReplayEngine(registry,options,(p,o) -> { launches.incrementAndGet(); throw new IllegalStateException("PRIVATE_EXCEPTION"); },
                new DiagnosticStore(directory),null);
        var unknown = engine.run(new TenantId("unknownScope"),fixture(),parameters());
        failure(unknown,UNKNOWN_TENANT);
        assertEquals(ReplayResult.Disposition.HARD_FAILURE,unknown.disposition());
        assertEquals(ReplayResult.DiagnosticPersistence.STORED,unknown.diagnosticPersistence());
        var missing = engine.run(b,fixture(),parameters());
        failure(missing,UNKNOWN_TENANT_TARGET);
        assertEquals(ReplayResult.Disposition.HARD_FAILURE,missing.disposition());
        assertEquals(ReplayDiagnostic.Phase.TARGET_RESOLUTION,missing.diagnostic().phase());
        var other = artifact(); ((ObjectNode)other.get("target")).put("targetId","only-in-b");
        failure(engine.run(a,other.toString(),parameters()),UNKNOWN_TENANT_TARGET);
        var injected = artifact(); ((ObjectNode)injected.get("target")).put("origin",origin(portB));
        failure(engine.run(a,injected.toString(),parameters()),INVALID_ARTIFACT);
        injected = artifact(); ((ObjectNode)injected.get("target")).put("entryPath",origin(portB)+"/legacy");
        failure(engine.run(a,injected.toString(),parameters()),INVALID_ARTIFACT);
        for (String key : List.of("tenantId","policy","sessionExpiryMarker","redactionPolicy")) {
            injected = artifact(); injected.put(key,"PRIVATE_TOKEN");
            failure(engine.run(a,injected.toString(),parameters()),INVALID_ARTIFACT);
        }
        failure(engine.run(fixture(),parameters()),INVALID_PARAMETERS); // No implicit tenant on a native engine.
        assertEquals(0,launches.get()); assertEquals(0,count(portA)); assertEquals(0,count(portB));
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String data = Files.readString(file);
                for (String secret : List.of(a.value(),b.value(),"unknownScope","PRIVATE","http"))
                    assertFalse((file.getFileName()+data).contains(secret));
            }
        }
    }

    @Test void tenantAdditionsAffectOnlyDiagnosticsAndPersistenceNotExecution() throws Exception {
        var redaction = new DiagnosticRedactionPolicy(List.of("Open"),List.of("step-[0-9]{1,2}","Sav[a-z]{4}"));
        Path directory = temporary.resolve("diagnostics");
        var engine = new ReplayEngine(registry(redaction),options,new DiagnosticStore(directory),null);
        exact(engine.run(a,fixture(),parameters()));
        exact(engine.run(b,fixture(),parameters()));
        assertFalse(Files.exists(directory));
        String disabled = "<h1>Member Details</h1><table><tr><td>Savings</td><td>"
                + "<a aria-disabled='true' href='/legacy/accounts/SAV-2048'>Open</a></td></tr></table>";
        pages.put(portA,Map.of("/legacy/members/100042",disabled));
        pages.put(portB,Map.of("/legacy/members/100042",disabled));
        var privateResult = engine.run(a,fixture(),parameters());
        var normalResult = engine.run(b,fixture(),parameters());
        failure(privateResult,POSTCONDITION_FAILED); failure(normalResult,POSTCONDITION_FAILED);
        assertEquals(4,privateResult.step()); assertEquals(4,normalResult.step());
        assertEquals("[REDACTED]",privateResult.diagnostic().stepId());
        assertEquals("[REDACTED]",privateResult.diagnostic().expected().name());
        assertEquals("[REDACTED]",privateResult.diagnostic().expected().context().text());
        assertEquals("step-4",normalResult.diagnostic().stepId());
        assertEquals("Open",normalResult.diagnostic().expected().name());
        assertEquals("Savings",normalResult.diagnostic().expected().context().text());
        try (var files = Files.list(directory)) {
            var paths = files.toList(); assertEquals(2,paths.size());
            var written = new HashSet<ReplayDiagnostic>();
            for (var file : paths) {
                written.add(mapper.readValue(Files.readString(file),ReplayDiagnostic.class));
                assertFalse(file.getFileName().toString().contains(a.value()));
                assertFalse(file.getFileName().toString().contains(b.value()));
            }
            assertEquals(Set.of(privateResult.diagnostic(),normalResult.diagnostic()),written);
        }
    }

    @Test void differentlyCasedTenantOutcomeIsRejectedBeforeLaunchAndNeverPersisted() throws Exception {
        var launches = new AtomicInteger();
        Path directory = temporary.resolve("diagnostics");
        var engine = new ReplayEngine(registry(DiagnosticRedactionPolicy.baseline()), options,
                (p,o) -> { launches.incrementAndGet(); throw new IllegalStateException("PRIVATE_EXCEPTION"); },
                new DiagnosticStore(directory), null);
        var json = artifact();
        ((ObjectNode)json.at("/outcomes/0")).put("code", "LABTENANTA_ERROR");
        var result = engine.run(a, json.toString(), parameters());
        failure(result, INVALID_ARTIFACT);
        assertEquals(0, launches.get());
        assertEquals(ReplayResult.DiagnosticPersistence.STORED, result.diagnosticPersistence());
        try (var files = Files.list(directory)) {
            var paths = files.toList();
            assertEquals(1, paths.size());
            String presentation = result.toString() + result.diagnostic()
                    + paths.getFirst().getFileName() + Files.readString(paths.getFirst());
            assertFalse(presentation.toLowerCase(Locale.ROOT).contains(a.value().toLowerCase(Locale.ROOT)));
        }
    }

    @Test void tenantIdentifiersInArtifactExpectationsAreRedactedAndCannotBecomeOutcomeText() throws Exception {
        var engine = new ReplayEngine(registry(DiagnosticRedactionPolicy.baseline()),options);
        var json = artifact();
        ((ObjectNode)json.at("/steps/0")).put("id","step_"+a.value());
        ((ObjectNode)json.at("/steps/0/locator")).put("accessibleName",b.value());
        ((ObjectNode)json.at("/steps/0/postcondition/locator")).put("accessibleName",b.value());
        var result = engine.run(a,json.toString(),parameters());
        failure(result,ZERO_LOCATOR);
        assertEquals("[REDACTED]",result.diagnostic().stepId());
        assertEquals("[REDACTED]",result.diagnostic().expected().name());
        json = artifact(); ((ObjectNode)json.at("/outcomes/0")).put("code",a.value());
        failure(engine.run(a,json.toString(),parameters()),INVALID_ARTIFACT);
    }
}
