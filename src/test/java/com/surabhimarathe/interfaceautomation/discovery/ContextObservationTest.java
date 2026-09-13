package com.surabhimarathe.interfaceautomation.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContextObservationTest {
    @LocalServerPort int port;
    @Test void duplicateOpenLinksHaveDistinctVisibleRowContext() {
        String origin = "http://localhost:" + port;
        try (var session = new BrowserSession(DiscoveryContractsTest.policy(origin), true)) {
            var o = session.open(origin + "/legacy/members/100042");
            var links = o.controls().stream().filter(c -> c.name().equals("Open")).toList();
            assertEquals(2, links.size());
            var account = links.stream().filter(c -> c.context().contains("SAV-2048") && c.context().contains("Savings")).findFirst().orElseThrow();
            assertTrue(links.stream().anyMatch(c -> c.context().contains("Member profile")));
            assertEquals(ActionResult.Status.SUCCEEDED, session.execute(new UiAction(o.id(), UiAction.Type.CLICK, account.id(), "")).status());
            assertEquals("Savings Account", session.observe().heading());
        }
    }
    @Test void fullOfflineUiPathExtractsReviewAndExposesFormState() {
        String origin = "http://localhost:" + port;
        try (var session = new BrowserSession(DiscoveryContractsTest.policy(origin), true)) {
            var form = session.open(origin + "/legacy/accounts/SAV-2048/fee-reversal");
            assertTrue(form.controls().stream().filter(c -> c.name().equals("Amount")).findFirst().orElseThrow().context().contains("Reason"));
            fill(session, "Amount", "25.00"); fill(session, "Reason", "Courtesy adjustment");
            var o = session.observe();
            assertTrue(o.controls().stream().filter(c -> c.name().equals("Amount")).findFirst().orElseThrow().filled());
            var review = o.controls().stream().filter(c -> c.name().equals("Review reversal")).findFirst().orElseThrow();
            session.execute(new UiAction(o.id(), UiAction.Type.CLICK, review.id(), ""));
            var observed = session.observe();
            var details = ReviewCheckpoint.verify(observed, DiscoveryRunnerTest.REQUEST);
            assertNotNull(details, () -> observed.toString());
            assertEquals("SAV-2048", details.accountId());
        }
    }
    private void fill(BrowserSession session, String name, String value) {
        var o = session.observe();
        var c = o.controls().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
        assertEquals(ActionResult.Status.SUCCEEDED, session.execute(new UiAction(o.id(), UiAction.Type.FILL, c.id(), value)).status());
    }
}
