package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.artifact.ArtifactJson;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReplayPresentationTest {
    private final Map<String, Object> sensitive = Map.of(
            "memberId", "100042", "memberName", "Morgan Lee", "accountId", "SAV-2048",
            "reason", "Courtesy adjustment", "balance", "1842.73",
            "page", "No member matches that ID.", "url", "http://localhost/legacy/members/100042",
            "exception", "PRIVATE_EXCEPTION_DETAILS");

    @Test void validatedBusinessOutcomesHaveDistinctRedactedPrintRepresentations() throws Exception {
        try (var in = getClass().getResourceAsStream("/artifacts/prepare-fee-reversal-review.v1.json")) {
            assertNotNull(in);
            var artifact = new ArtifactJson().read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            var rendered = new ArrayList<String>();
            for (var outcome : artifact.outcomes()) {
                // Identifiers come from the validated artifact, never from visible page text.
                var result = new ReplayResult(ReplayResult.Status.EXPECTED_OUTCOME,
                        ReplayResult.Code.BUSINESS_OUTCOME, outcome.code(), 2, sensitive);
                String expected = "ReplayResult[status=EXPECTED_OUTCOME, code=" + outcome.code()
                        + ", step=2, outputs=REDACTED]";
                assertEquals(expected, result.toString());
                var bytes = new ByteArrayOutputStream();
                try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
                    out.println(result); // The same presentation operation used by ReplayCli.
                }
                String printed = bytes.toString(StandardCharsets.UTF_8);
                assertEquals(expected + System.lineSeparator(), printed);
                assertFalse(printed.contains("BUSINESS_OUTCOME"));
                assertRedacted(printed);
                rendered.add(printed);
            }
            assertEquals(2, rendered.size());
            assertTrue(rendered.get(0).contains("code=MEMBER_NOT_FOUND"));
            assertTrue(rendered.get(1).contains("code=VALIDATION_REJECTED"));
            assertNotEquals(rendered.get(0), rendered.get(1));
        }
    }

    @Test void successAndFailurePresentationRemainRedacted() {
        var success = new ReplayResult(ReplayResult.Status.SUCCEEDED,
                ReplayResult.Code.CHECKPOINT_VERIFIED, null, 8, sensitive);
        assertTrue(success.toString().contains("code=CHECKPOINT_VERIFIED"));
        assertRedacted(success.toString());
        var failure = ReplayResult.failure(ReplayResult.Code.BROWSER_FAILURE, 3);
        assertTrue(failure.toString().contains("code=BROWSER_FAILURE"));
        assertRedacted(failure.toString());
    }

    private void assertRedacted(String text) {
        for (Object value : sensitive.values()) assertFalse(text.contains((String) value), "Sensitive value leaked");
        assertFalse(text.contains("http"));
    }
}
