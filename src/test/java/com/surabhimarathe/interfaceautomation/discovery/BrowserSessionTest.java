package com.surabhimarathe.interfaceautomation.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BrowserSessionTest {
    @LocalServerPort int port;
    @Test void persistsFilledStateRejectsStaleActionAndNavigatesOnOwnerThread() {
        String origin = "http://localhost:" + port;
        try (var session = new BrowserSession(DiscoveryContractsTest.policy(origin), true)) {
            var before = session.open(origin + "/legacy");
            var field = before.controls().stream().filter(c -> c.name().equals("Member ID")).findFirst().orElseThrow();
            var fill = new UiAction(before.id(), UiAction.Type.FILL, field.id(), "100042");
            var filled = session.execute(fill);
            assertEquals(ActionResult.Code.VALUE_VERIFIED, filled.code());
            assertNotEquals(before.id(), filled.observation().id());
            assertEquals(ActionResult.Code.STALE_OBSERVATION, session.execute(fill).code());
            var current = session.observe();
            var search = current.controls().stream().filter(c -> c.name().equals("Search")).findFirst().orElseThrow();
            var searched = session.execute(new UiAction(current.id(), UiAction.Type.CLICK, search.id(), ""));
            assertEquals(ActionResult.Status.SUCCEEDED, searched.status());
            assertEquals("Search Results", searched.observation().heading());
            assertTrue(searched.observation().controls().stream().anyMatch(c -> c.name().equals("Open")));
        }
    }
    @Test void blocksSubmitBeforeClickAndOffOriginEntry() {
        String origin = "http://localhost:" + port;
        try (var session = new BrowserSession(DiscoveryContractsTest.policy(origin), true)) {
            assertThrows(IllegalStateException.class, () -> session.open("http://example.com/legacy"));
            var before = session.open(origin + "/legacy");
            // Even a valid target cannot be used as another action type.
            var button = before.controls().stream().filter(c -> c.name().equals("Search")).findFirst().orElseThrow();
            assertEquals(ActionResult.Code.POLICY_DENIED,
                session.execute(new UiAction(before.id(), UiAction.Type.FILL, button.id(), "secret")).code());
            session.open(origin + "/legacy/accounts/SAV-2048/fee-reversal");
            act(session, "Amount", UiAction.Type.FILL, "25.00");
            act(session, "Reason", UiAction.Type.FILL, "Courtesy adjustment");
            act(session, "Review reversal", UiAction.Type.CLICK, "");
            var review = session.observe();
            assertEquals("Fee Reversal Review", review.heading());
            var submit = review.controls().stream().filter(c -> c.name().equals("Submit reversal")).findFirst().orElseThrow();
            assertEquals(ActionResult.Code.POLICY_DENIED,
                session.execute(new UiAction(review.id(), UiAction.Type.CLICK, submit.id(), "")).code());
            assertEquals("Fee Reversal Review", session.observe().heading());
        }
    }

    private void act(BrowserSession session, String name, UiAction.Type type, String value) {
        var observed = session.observe();
        var target = observed.controls().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
        assertEquals(ActionResult.Status.SUCCEEDED,
            session.execute(new UiAction(observed.id(), type, target.id(), value)).status());
    }
}
