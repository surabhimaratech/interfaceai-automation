package com.surabhimarathe.interfaceautomation.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryContractsTest {
    static ActionPolicy policy(String origin) {
        return new ActionPolicy(origin, List.of(Pattern.compile("/legacy(?:/.*)?")), Set.of(UiAction.Type.FILL, UiAction.Type.CLICK));
    }
    @Test void rejectsBadActionsAndPolicyEscapes() {
        assertThrows(IllegalArgumentException.class, () -> new UiAction(UUID.randomUUID(), UiAction.Type.FILL, "body", "secret"));
        var p = policy("http://localhost:8080");
        for (String url : List.of("http://evil.test/legacy", "http://localhost:8080/legacy?token=secret",
            "http://localhost:8080/legacy/%2e%2e/admin", "http://localhost:8080/legacy/../admin",
            "http://localhost:8080/legacy/accounts/SAV-2048/fee-reversal/submit",
            "http://localhost:8080@evil.test/legacy"))
            assertFalse(p.allowsUrl(url), url);
        var click = new UiAction(UUID.randomUUID(), UiAction.Type.CLICK, "c0", "");
        assertFalse(p.permits(click, "http://localhost:8080/legacy", "http://localhost:8080/legacy", "Submit reversal"));
        assertFalse(p.permits(click, "http://localhost:8080/legacy", "http://evil.test/legacy", "Open"));
        assertTrue(p.permits(click, "http://localhost:8080/legacy", "http://localhost:8080/legacy/members/search", "Search"));
        var restricted = new ActionPolicy(p.origin(), p.routes(), Set.of(UiAction.Type.FILL));
        assertFalse(restricted.permits(click, "http://localhost:8080/legacy", "http://localhost:8080/legacy", "Open"));
    }
    @Test void logsOnlyFixedMetadataAndNeverOverwrites(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("events.jsonl");
        var log = new SafeEvents(file);
        log.record(RunState.ACTING, UiAction.Type.FILL, null, "sk-secret\nPII", true);
        log.record(RunState.SUCCEEDED, UiAction.Type.FILL, ActionResult.Code.VALUE_VERIFIED, null, true);
        String output = Files.readString(file);
        assertEquals(2, output.lines().count());
        assertFalse(output.contains("sk-secret"));
        assertFalse(output.contains("PII"));
        assertTrue(output.contains("VALUE_VERIFIED"));
        assertThrows(FileAlreadyExistsException.class, () -> new SafeEvents(file));
    }
}
