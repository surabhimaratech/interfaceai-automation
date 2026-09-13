package com.surabhimarathe.interfaceautomation.discovery;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Local operator controls, deliberately outside the automation route allowlist. */
@RestController
@ConditionalOnProperty(name = "discovery.flow", havingValue = "true")
public final class OperatorController {
    private final DiscoveryFlow flow;
    public OperatorController(DiscoveryFlow flow) { this.flow = flow; }
    @GetMapping(value = "/operator", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(HttpServletRequest req) {
        if (!local(req)) return ResponseEntity.status(403).build();
        var h = flow.handoff();
        var s = h.status();
        String form = s.owner() == HandoffCoordinator.Owner.HUMAN ?
            "<form method='post' action='/operator/resume'><input type='hidden' name='token' value='" + h.resumeToken()
                + "'><button>Resume automation</button></form>" : "";
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .header("Content-Security-Policy", "default-src 'none'; form-action 'self'; frame-ancestors 'none'")
            .body("<!doctype html><html lang='en'><meta charset='utf-8'><title>Discovery operator</title>"
                + "<h1>Discovery operator</h1><p>Owner: " + s.owner() + "</p><p>Reason: " + s.reason()
                + "</p><p>Current step: " + s.step() + "</p><p>Session epoch: " + s.epoch()
                + "</p><p>Manual input events: " + s.interactions()
                + "</p><p>Operate the existing headed Chromium window, then resume here. Do not submit a reversal.</p>"
                + form + "<p><a href='/operator'>Refresh status</a></p></html>");
    }
    @GetMapping("/operator/status")
    public ResponseEntity<HandoffCoordinator.Status> status(HttpServletRequest req) {
        return local(req) ? ResponseEntity.ok().header("Cache-Control", "no-store").body(flow.handoff().status())
            : ResponseEntity.status(403).build();
    }
    @PostMapping(value = "/operator/resume", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> resume(@RequestParam String token, HttpServletRequest req) throws java.io.IOException {
        if (!local(req)) return ResponseEntity.status(403).build();
        String origin = req.getHeader("Origin");
        if (origin != null && !origin.equals(req.getScheme() + "://" + req.getServerName() + ":" + req.getServerPort()))
            return ResponseEntity.status(403).build();
        if (!flow.handoff().resume(token)) return ResponseEntity.status(409).build();
        return ResponseEntity.status(303).header("Location", "/operator").build();
    }
    private boolean local(HttpServletRequest req) {
        return java.util.Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(req.getRemoteAddr())
            && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(req.getServerName());
    }
}
