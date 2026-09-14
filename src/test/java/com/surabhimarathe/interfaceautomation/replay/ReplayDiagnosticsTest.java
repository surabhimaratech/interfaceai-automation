package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.artifact.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReplayDiagnosticsTest {
    LocatorSpec locator(String name, String context) {
        return new LocatorSpec(LocatorSpec.Role.LINK, name, LocatorSpec.NameMatch.EXACT,
                context == null ? null : new ContextSpec(ContextSpec.Kind.ROW, context), LocatorSpec.Cardinality.EXACT_ONE);
    }
    @Test void sanitizerRetainsOnlyBoundedUnexpandedArtifactExpectations() {
        var diagnostics = new ReplayDiagnostics(Map.of("memberId","100042","reason","Courtesy adjustment"));
        diagnostics.step(4,"step-4");
        diagnostics.phase(ReplayDiagnostic.Phase.LOCATOR);
        diagnostics.expected(locator("Open","${inputs.memberId}"));
        diagnostics.count(0);
        var safe = diagnostics.snapshot(ReplayResult.Code.ZERO_LOCATOR);
        assertEquals("step-4",safe.stepId());
        assertEquals("${inputs.memberId}",safe.expected().context().text());
        assertEquals("Open",safe.expected().name());
        for (String unsafe : new String[]{"100042","Courtesy adjustment","https://private/thing","user@example.com",
                "token-value","<html>","A\nB","a".repeat(301)}) {
            diagnostics.expected(locator(unsafe,unsafe));
            var redacted = diagnostics.snapshot(ReplayResult.Code.ZERO_LOCATOR);
            assertEquals("[REDACTED]",redacted.expected().name());
            assertEquals("[REDACTED]",redacted.expected().context().text());
        }
        diagnostics.step(2,"private-100042");
        assertEquals("[REDACTED]",diagnostics.snapshot(ReplayResult.Code.ZERO_LOCATOR).stepId());
    }
    @Test void restoringProbeContextCannotEraseStickySafetyFlags() {
        var diagnostics = new ReplayDiagnostics(Map.of());
        diagnostics.phase(ReplayDiagnostic.Phase.LOCATOR);
        diagnostics.expected(locator("Open","Savings"));
        diagnostics.count(2);
        var lookup = diagnostics.snapshot(ReplayResult.Code.AMBIGUOUS_LOCATOR);
        diagnostics.phase(ReplayDiagnostic.Phase.OUTCOME);
        diagnostics.dialog();
        diagnostics.restore(lookup);
        assertEquals(ReplayResult.Code.UNEXPECTED_DIALOG,diagnostics.safetyCode(ReplayResult.Code.TIMEOUT));
        assertEquals("Savings",diagnostics.snapshot(ReplayResult.Code.UNEXPECTED_DIALOG).expected().context().text());
        diagnostics.deniedRequest();
        diagnostics.restore(lookup);
        assertEquals(ReplayResult.Code.POLICY_DENIED,diagnostics.safetyCode(ReplayResult.Code.TIMEOUT));
        assertTrue(diagnostics.snapshot(ReplayResult.Code.POLICY_DENIED).conditions().requestDenied());
        assertTrue(diagnostics.snapshot(ReplayResult.Code.POLICY_DENIED).conditions().unexpectedDialog());
    }
    @Test void unmeasuredAndBoundedAmbiguousCountsAreDistinctFromZero() {
        var diagnostics = new ReplayDiagnostics(Map.of());
        assertEquals(-1,diagnostics.snapshot(ReplayResult.Code.TIMEOUT).matches().visible());
        assertFalse(diagnostics.snapshot(ReplayResult.Code.TIMEOUT).matches().zero());
        diagnostics.count(0);
        assertTrue(diagnostics.snapshot(ReplayResult.Code.ZERO_LOCATOR).matches().zero());
        diagnostics.count(1000);
        var capped = diagnostics.snapshot(ReplayResult.Code.AMBIGUOUS_LOCATOR).matches();
        assertEquals(201,capped.visible());
        assertTrue(capped.capped());
        assertTrue(capped.ambiguous());
    }
    @Test void nonRetryableCodesNeverReceiveRecoverableDisposition() {
        for (var code : new ReplayResult.Code[]{ReplayResult.Code.POLICY_DENIED,ReplayResult.Code.UNEXPECTED_DIALOG,
                ReplayResult.Code.INTERRUPTED,ReplayResult.Code.BROWSER_FAILURE,ReplayResult.Code.CHECKPOINT_FAILED}) {
            var result = ReplayResult.failure(code,2);
            assertNotEquals(ReplayResult.Disposition.RECOVERABLE,result.disposition());
            assertTrue(result.toString().contains("disposition="+result.disposition()));
            assertTrue(result.toString().contains("code="+code));
            assertTrue(result.toString().contains("step=2"));
            assertNotNull(result.diagnostic());
        }
        assertEquals(ReplayResult.Disposition.RECOVERABLE,
                ReplayResult.failure(ReplayResult.Code.TIMEOUT,2).disposition());
    }
}
