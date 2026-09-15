package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.artifact.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticRedactionPolicyTest {
    @Test void invocationSecretInStepIdIsRedactedRegardlessOfCase(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var diagnostics = new ReplayDiagnostics(Map.of("name", "MorganLee"));
        diagnostics.step(1, "step_morganlee");
        var result = diagnostics.failure(ReplayResult.Code.ZERO_LOCATOR);
        assertEquals("[REDACTED]", result.diagnostic().stepId());
        new DiagnosticStore(directory).persist(result.diagnostic());
        try (var files = java.nio.file.Files.list(directory)) {
            var paths = files.toList();
            assertEquals(1, paths.size());
            String presentation = result.toString() + result.diagnostic()
                    + java.nio.file.Files.readString(paths.getFirst());
            assertFalse(presentation.toLowerCase(Locale.ROOT).contains("morganlee"));
        }
    }

    LocatorSpec locator(String name, String context) {
        return new LocatorSpec(LocatorSpec.Role.LINK,name,LocatorSpec.NameMatch.EXACT,
                context == null ? null : new ContextSpec(ContextSpec.Kind.ROW,context),LocatorSpec.Cardinality.EXACT_ONE);
    }
    @Test void leastRestrictivePolicyCannotDisableBaselineOrTenantSuppression() {
        var d = new ReplayDiagnostics(Map.of("memberId","100042","reason","Courtesy adjustment"),
                new DiagnosticRedactionPolicy(List.of(),List.of()),List.of("labTenantA"));
        for (String value : List.of("100042","Courtesy adjustment","user@example.com","https://private/path",
                "sk-private","password-value","<html>","A\nB","labTenantA")) {
            d.expected(locator(value,value));
            var diagnostic = d.snapshot(ReplayResult.Code.ZERO_LOCATOR);
            assertEquals("[REDACTED]",diagnostic.expected().name());
            assertEquals("[REDACTED]",diagnostic.expected().context().text());
        }
        d.step(1,"step_100042"); assertEquals("[REDACTED]",d.snapshot(null).stepId());
        d.step(1,"step_labTenantA"); assertEquals("[REDACTED]",d.snapshot(null).stepId());
        d.expected(locator("${inputs.memberId}",null));
        assertEquals("${inputs.memberId}",d.snapshot(null).expected().name());
    }
    @Test void additionsAreImmutableCaseInsensitiveLiteralsAndBoundedPatterns() {
        var literals = new ArrayList<>(List.of("Open"));
        var patterns = new ArrayList<>(List.of("Sav[a-z]{4}","step-[0-9]{1,2}"));
        var policy = new DiagnosticRedactionPolicy(literals,patterns);
        literals.clear(); patterns.clear();
        var d = new ReplayDiagnostics(Map.of(),policy,List.of());
        d.step(4,"step-4"); d.expected(locator("OPEN","Savings"));
        var diagnostic = d.snapshot(ReplayResult.Code.POSTCONDITION_FAILED);
        assertEquals("[REDACTED]",diagnostic.stepId());
        assertEquals("[REDACTED]",diagnostic.expected().name());
        assertEquals("[REDACTED]",diagnostic.expected().context().text());
        assertEquals("Other label",policy.apply("Other label"));
        assertFalse(policy.toString().contains("Open"));
    }
    @Test void safePatternsMatchWithoutGeneralRegexOrBacktracking() {
        var policy = new DiagnosticRedactionPolicy(List.of(),List.of("Ab[A-Za-z]{1,3}","Ref-[A-Z0-9]{2}"));
        assertEquals("[REDACTED]",policy.apply("prefix Abc suffix"));
        assertEquals("[REDACTED]",policy.apply("Ref-A2"));
        assertEquals("Ref-a2",policy.apply("Ref-a2"));
        assertEquals("Other",policy.apply("Other"));
        var bounded = new DiagnosticRedactionPolicy(List.of(),List.of("a{1,32}".repeat(7)+"b"));
        assertTimeout(Duration.ofSeconds(1),() -> bounded.apply("a".repeat(300)));
    }
    @Test void unsafeOrMalformedConfigurationFailsBeforeBrowserLaunch() {
        var launches = new AtomicInteger();
        for (String pattern : new String[]{""," ","a".repeat(161),"(a+)+$","a.*b","a+","a?","(ab|cd)",
                "(?=a)","\\1","\\d","[x-z]","[A-Z","[A-Z]*","a{0,2}","a{2,1}","a{33}",
                "a{1,}","a{01,99}","a{1,32}".repeat(9),"é","A\nB"}) {
            var error = assertThrows(IllegalArgumentException.class,() -> {
                var policy = new DiagnosticRedactionPolicy(List.of(),List.of(pattern));
                var configuration = new TenantTargetConfiguration(
                        new com.surabhimarathe.interfaceautomation.discovery.ActionPolicy("http://localhost:1",List.of(),Set.of(),Map.of()),null,policy);
                var registry = new TenantTargetRegistry(Map.of(new TenantId("labTenantA"),Map.of("legacy-banking",configuration)));
                new ReplayEngine(registry,ReplayOptions.defaults(),
                        (p,o) -> { launches.incrementAndGet(); throw new IllegalStateException("PRIVATE"); },null,null)
                        .runValidation(new TenantId("labTenantA"),"{}",new InvocationParameters(Map.of()));
            });
            assertEquals("INVALID_REDACTION_CONFIGURATION",error.getMessage());
        }
        assertEquals(0,launches.get());
    }
    @Test void nullBlankOversizedAndExcessiveLiteralOrPatternListsAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> new DiagnosticRedactionPolicy(null,List.of()));
        assertThrows(IllegalArgumentException.class,() -> new DiagnosticRedactionPolicy(List.of(),null));
        for (String secret : new String[]{null,""," ","A".repeat(161),"A\nB"})
            assertThrows(IllegalArgumentException.class,() -> new DiagnosticRedactionPolicy(Arrays.asList(secret),List.of()));
        assertThrows(IllegalArgumentException.class,() -> new DiagnosticRedactionPolicy(Collections.nCopies(33,"secret"),List.of()));
        assertThrows(IllegalArgumentException.class,() -> new DiagnosticRedactionPolicy(List.of(),Collections.nCopies(17,"A")));
        assertThrows(IllegalArgumentException.class,() -> new DiagnosticRedactionPolicy(List.of(),Arrays.asList((String)null)));
    }
    @Test void tenantIdentifiersAreValidatedAndNeverPrinted() {
        for (String invalid : new String[]{null,""," ","a".repeat(65),"../tenant","tenant@example","A\nB","1tenant"})
            assertThrows(IllegalArgumentException.class,() -> new TenantId(invalid));
        assertEquals("a".repeat(64),new TenantId("a".repeat(64)).value());
        assertEquals("TenantId[REDACTED]",new TenantId("labTenantA").toString());
    }
    @Test void registryCopiesBothLevelsAndHasNoCrossTenantOrDefaultFallback() {
        var a = new TenantId("labTenantA"); var b = new TenantId("labTenantB");
        var policy = new com.surabhimarathe.interfaceautomation.discovery.ActionPolicy("http://localhost:1",List.of(),Set.of(),Map.of());
        var config = new TenantTargetConfiguration(policy,null,DiagnosticRedactionPolicy.baseline());
        var targets = new HashMap<>(Map.of("only-a",config));
        var tenants = new HashMap<TenantId,Map<String,TenantTargetConfiguration>>();
        tenants.put(a,targets); tenants.put(b,Map.of());
        var registry = new TenantTargetRegistry(tenants);
        targets.clear(); tenants.clear();
        assertSame(config,registry.resolve(a,"only-a"));
        assertNull(registry.resolve(b,"only-a"));
        assertNull(registry.resolve(new TenantId("missing"),"only-a"));
        assertNull(registry.resolve(null,"only-a"));
        assertFalse(registry.toString().contains(a.value()));
        assertFalse(config.toString().contains("localhost"));
    }
}
