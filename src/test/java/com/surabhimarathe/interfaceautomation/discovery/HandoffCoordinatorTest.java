package com.surabhimarathe.interfaceautomation.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class HandoffCoordinatorTest {
    @TempDir Path temp;
    @Test void singleUseSignalOwnershipAndRedaction() throws Exception {
        var h = new HandoffCoordinator();
        var file = temp.resolve("events.jsonl"); h.attach(new SafeEvents(file));
        h.request(HandoffCoordinator.Reason.MODEL_REQUEST, 4);
        assertEquals(HandoffCoordinator.Owner.HUMAN, h.status().owner());
        assertThrows(IllegalStateException.class, h::requireAutomation);
        assertThrows(IllegalStateException.class, () -> h.request(HandoffCoordinator.Reason.MODEL_REQUEST, 5));
        String token = h.resumeToken();
        assertFalse(h.resume("wrong"));
        h.interaction();
        assertTrue(h.resume(token));
        assertEquals(HandoffCoordinator.Owner.RESUMING, h.status().owner());
        assertThrows(IllegalStateException.class, h::requireAutomation);
        assertFalse(h.resume(token));
        h.reclaim(); h.requireAutomation();
        assertEquals(1, h.status().interactions());
        h.request(HandoffCoordinator.Reason.SIMULATED_SESSION_EXPIRY, 5);
        assertFalse(h.resume(token));
        assertEquals(2, h.status().epoch());
        h.close();
        assertFalse(h.resume(h.resumeToken()));
        String log = Files.readString(file);
        assertFalse(log.contains(token));
        assertTrue(log.contains("CONTROL_TRANSFER"));
        assertTrue(log.contains("manualInputEvents"));
    }
    @Test void resumeRequiresHumanAndReclaimRequiresSignal() throws Exception {
        var h = new HandoffCoordinator();
        assertFalse(h.resume(""));
        assertThrows(IllegalStateException.class, h::reclaim);
        h.close();
        assertThrows(IllegalStateException.class, () -> h.request(HandoffCoordinator.Reason.MODEL_REQUEST, 0));
    }
    @Test void operatorRejectsForeignOriginsAndStaleSignals() throws Exception {
        var flow = new DiscoveryFlow(new org.springframework.mock.env.MockEnvironment());
        var controller = new OperatorController(flow);
        var req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1"); req.setServerName("localhost"); req.setServerPort(8080);
        flow.handoff().request(HandoffCoordinator.Reason.MODEL_REQUEST, 0);
        String token = flow.handoff().resumeToken();
        req.addHeader("Origin", "https://evil.example");
        assertEquals(403, controller.resume(token, req).getStatusCode().value());
        req.removeHeader("Origin"); req.addHeader("Origin", "http://localhost:8080");
        assertEquals(303, controller.resume(token, req).getStatusCode().value());
        assertEquals(409, controller.resume(token, req).getStatusCode().value());
        req.setRemoteAddr("192.0.2.1");
        assertEquals(403, controller.page(req).getStatusCode().value());
    }
}
