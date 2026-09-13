package com.surabhimarathe.interfaceautomation.discovery;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HandoffIntegrationTest {
    @LocalServerPort int port;
    @TempDir Path temp;
    @Test void testOperatorChangesSamePageWhileAutomationIsBlockedThenFreshResume() throws Exception {
        int cdpPort;
        try (var socket = new ServerSocket(0)) { cdpPort = socket.getLocalPort(); }
        String origin = "http://localhost:" + port;
        var h = new HandoffCoordinator(); h.attach(new SafeEvents(temp.resolve("handoff.jsonl")));
        try (var session = new BrowserSession(DiscoveryContractsTest.policy(origin), true,
                List.of("--remote-debugging-port=" + cdpPort));
             var operator = Playwright.create()) {
            session.attachHandoff(h);
            var initial = session.open(origin + "/legacy");
            session.simulateExpiry();
            session.giveToHuman(HandoffCoordinator.Reason.SIMULATED_SESSION_EXPIRY, 0);
            assertThrows(IllegalStateException.class, session::observe);
            assertThrows(IllegalStateException.class, () -> session.open(origin + "/legacy"));
            assertThrows(IllegalStateException.class, () -> session.execute(new UiAction(initial.id(), UiAction.Type.FILL, "c0", "999999")));
            // A second CDP client stands in for the human ONLY in this offline integration test.
            // It attaches to the existing page/context; it does not create a replacement session.
            var remote = operator.chromium().connectOverCDP("http://localhost:" + cdpPort);
            assertEquals(1, remote.contexts().getFirst().pages().size());
            var page = remote.contexts().getFirst().pages().getFirst();
            // Like the runner's pause loop, dispatch route/binding callbacks while the operator navigates.
            var pumping = new java.util.concurrent.atomic.AtomicBoolean(true);
            try (var dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var pump = dispatcher.submit(() -> { while (pumping.get()) session.pumpHumanEvents(); });
                try {
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Restore session")).click();
                    page.getByLabel("Member ID").fill("100042");
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Search").setExact(true)).click();
                } finally {
                    pumping.set(false);
                    pump.get(5, java.util.concurrent.TimeUnit.SECONDS);
                }
            }
            session.pumpHumanEvents();
            assertTrue(h.status().interactions() > 0);
            assertTrue(h.resume(h.resumeToken()));
            session.reclaimFromHuman();
            var observed = session.observe();
            assertEquals("Search Results", observed.heading());
            assertNotEquals(initial.id(), observed.id());
            assertEquals(ActionResult.Code.STALE_OBSERVATION,
                session.execute(new UiAction(initial.id(), UiAction.Type.CLICK, "c1", "")).code());
            observed = session.observe();
            var open = observed.controls().stream().filter(c -> c.name().equals("Open")).findFirst().orElseThrow();
            assertEquals(ActionResult.Status.SUCCEEDED,
                session.execute(new UiAction(observed.id(), UiAction.Type.CLICK, open.id(), "")).status());
            assertEquals("Member Details", session.observe().heading());
            h.close();
        }
        String evidence = Files.readString(temp.resolve("handoff.jsonl"));
        assertFalse(evidence.contains("100042")); assertFalse(evidence.contains("Member ID"));
    }
}
