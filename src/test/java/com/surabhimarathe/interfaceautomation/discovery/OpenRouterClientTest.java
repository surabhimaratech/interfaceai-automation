package com.surabhimarathe.interfaceautomation.discovery;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OpenRouterClientTest {
    @Test void parsesRealHttpToolProtocolWithPinnedModel() throws Exception {
        var observation = new Observation(UUID.randomUUID(), "http://localhost:8080/legacy", "Member Search",
            List.of(new Observation.Control("c0", "textbox", "Member ID")));
        var json = new ObjectMapper();
        var action = new UiAction(observation.id(), UiAction.Type.FILL, "c0", "100042");
        String response = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls",
            "message", Map.of("tool_calls", List.of(Map.of("function", Map.of("name", "ui_action",
                "arguments", json.writeValueAsString(action)))))))));
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new OpenRouterClient("test-key", URI.create("http://localhost:" + server.getAddress().getPort()));
            assertEquals(action, client.decide("Enter the member ID", observation, java.time.Duration.ofSeconds(5),
                new PreviousAction(UiAction.Type.CLICK, ActionResult.Code.CLICK_COMPLETED, true)));
            var body = json.readTree(request.get());
            assertEquals("anthropic/claude-sonnet-5", body.path("model").asString());
            assertFalse(body.path("parallel_tool_calls").asBoolean());
            assertFalse(request.get().contains("test-key"));
            assertTrue(body.path("messages").path(1).path("content").asString().contains("CLICK_COMPLETED"));
        } finally { server.stop(0); }
    }
    @Test void rejectsMissingKeyAndMalformedOrProviderErrorWithoutLeakingBody() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new OpenRouterClient(""));
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        var status = new java.util.concurrent.atomic.AtomicInteger(401);
        server.createContext("/", exchange -> {
            byte[] bytes = "private-token-or-page-data".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var client = new OpenRouterClient("test-key", URI.create("http://localhost:" + server.getAddress().getPort()));
            var observation = new Observation(UUID.randomUUID(), "http://localhost/legacy", "", List.of());
            var error = assertThrows(OpenRouterClient.ModelFailure.class, () -> client.decide("secret goal", observation));
            assertEquals("MODEL_HTTP_401", error.getMessage());
            status.set(200);
            error = assertThrows(OpenRouterClient.ModelFailure.class, () -> client.decide("secret goal", observation));
            assertEquals("MODEL_REQUEST_FAILED", error.getMessage());
            assertNull(error.getCause());
        } finally { server.stop(0); }
    }
}
