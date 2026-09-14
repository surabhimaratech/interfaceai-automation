package com.surabhimarathe.interfaceautomation.replay;

import com.surabhimarathe.interfaceautomation.artifact.LocatorSpec;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.surabhimarathe.interfaceautomation.discovery.HandoffCoordinator.Owner.*;

class ReplayOperatorTest {
    String origin(ReplayOperatorServer server) { return "http://127.0.0.1:"+server.port(); }
    HttpResponse<String> get(ReplayOperatorServer server, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin(server)+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    int resume(ReplayOperatorServer server, String cookie, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin(server)+"/replay-operator/resume")).header("Cookie",cookie);
        if (origin != null) request.header("Origin",origin);
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    String cookie(HttpResponse<String> response) { return response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0]; }

    @Test void singleUseAndStaleSignalsRespectAllOwnershipStatesWithoutTokenPresentation() throws Exception {
        try (var handoff = new ReplayHandoff(Duration.ofSeconds(5),2);
             var server = new ReplayOperatorServer(handoff,0)) {
            handoff.requireAutomation();
            handoff.request(3);
            assertEquals(HUMAN,handoff.status().owner());
            assertThrows(IllegalStateException.class,handoff::requireAutomation);
            var page = get(server,"/replay-operator");
            String first = cookie(page), secret = first.substring(first.indexOf('=')+1);
            String status = get(server,"/replay-operator/status").body();
            assertFalse(secret.isBlank());
            for (String presented : new String[]{page.body(),status,handoff.toString()}) {
                assertFalse(presented.contains(secret),"Resume token must not be presented");
                assertFalse(presented.contains("PRIVATE"));
                assertFalse(presented.contains("http"));
            }
            assertEquals("no-store",page.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(page.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly"));
            assertEquals(403,resume(server,first,"http://elsewhere.invalid"));
            assertEquals(403,resume(server,first,null));
            assertEquals(409,resume(server,"replay_resume=stale",origin(server)));
            assertEquals(HUMAN,handoff.status().owner());
            assertEquals(303,resume(server,first,origin(server)));
            assertEquals(RESUMING,handoff.status().owner());
            assertThrows(IllegalStateException.class,handoff::requireAutomation);
            assertThrows(IllegalStateException.class,() -> handoff.request(4));
            assertEquals(409,resume(server,first,origin(server)));
            handoff.reclaim();
            assertEquals(AUTOMATION,handoff.status().owner());
            handoff.requireAutomation();
            handoff.request(4);
            String second = cookie(get(server,"/replay-operator"));
            assertFalse(first.equals(second),"Each epoch needs a fresh token");
            assertEquals(409,resume(server,first,origin(server)));
            assertEquals(303,resume(server,second,origin(server)));
            handoff.close();
            assertEquals(409,resume(server,second,origin(server)));
            assertThrows(IllegalStateException.class,handoff::requireAutomation);
            assertFalse(get(server,"/replay-operator/status").body().contains(secret));
        }
    }

    @Test void operatorRejectsQuerySignalsAndHasNoDiscoveryRoute() throws Exception {
        try (var handoff = new ReplayHandoff(Duration.ofSeconds(5),1);
             var server = new ReplayOperatorServer(handoff,0)) {
            assertEquals(403,get(server,"/replay-operator?resume=ignored").statusCode());
            assertEquals(404,get(server,"/operator").statusCode());
            assertEquals(404,get(server,"/replay-operator/unknown").statusCode());
            assertFalse(get(server,"/replay-operator").body().contains("<input"));
        }
    }

    @Test void trustedMarkerAndHandoffConfigurationAreBounded() {
        for (String name : new String[]{""," ","A".repeat(161),"SESSION\nEXPIRED","${inputs.reason}"})
            assertThrows(IllegalArgumentException.class,() -> new SessionExpiryMarker(LocatorSpec.Role.ALERT,name));
        assertThrows(IllegalArgumentException.class,() -> new SessionExpiryMarker(LocatorSpec.Role.BUTTON,"Expired"));
        assertThrows(IllegalArgumentException.class,() -> new TargetRegistry(Map.of(),Map.of("unknown",
                new SessionExpiryMarker(LocatorSpec.Role.ALERT,"Expired"))));
        assertThrows(IllegalArgumentException.class,() -> new ReplayHandoff(Duration.ZERO,1));
        assertThrows(IllegalArgumentException.class,() -> new ReplayHandoff(Duration.ofMinutes(6),1));
        assertThrows(IllegalArgumentException.class,() -> new ReplayHandoff(Duration.ofSeconds(1),0));
        assertThrows(IllegalArgumentException.class,() -> new ReplayHandoff(Duration.ofSeconds(1),11));
        assertFalse(new SessionExpiryMarker(LocatorSpec.Role.ALERT,"PRIVATE_CONFIG").toString().contains("PRIVATE_CONFIG"));
    }

    @Test void headlessHandoffIsRejectedBeforeBrowserLaunch() throws Exception {
        var launches = new AtomicInteger();
        try (var handoff = new ReplayHandoff(Duration.ofSeconds(1),1)) {
            var engine = new ReplayEngine(new TargetRegistry(Map.of()),
                    new ReplayOptions(Duration.ofSeconds(1),Duration.ofSeconds(3),true),
                    (p,o) -> { launches.incrementAndGet(); throw new IllegalStateException("PRIVATE"); },null,handoff);
            var result = engine.run("{}",new InvocationParameters(Map.of()));
            assertEquals(ReplayResult.Code.INVALID_PARAMETERS,result.code());
            assertEquals(0,launches.get());
        }
    }
}
