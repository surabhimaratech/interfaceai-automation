package com.surabhimarathe.interfaceautomation.replay;

import com.sun.net.httpserver.*;
import com.surabhimarathe.interfaceautomation.discovery.HandoffCoordinator;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Separate loopback-only operator surface. Tokens travel only in HttpOnly cookies, never page/status bodies. */
public final class ReplayOperatorServer implements AutoCloseable {
    private static final String ROOT = "/replay-operator";
    private static final String COOKIE = "replay_resume";
    private final HttpServer server;
    private final ExecutorService executor;
    private final ReplayHandoff handoff;
    public ReplayOperatorServer(ReplayHandoff handoff, int port) throws IOException {
        if (handoff == null || port < 0 || port > 65535) throw new IllegalArgumentException("INVALID_OPERATOR_CONFIGURATION");
        this.handoff = handoff;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),0);
        executor = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r,"replay-operator"); t.setDaemon(true); return t; });
        server.setExecutor(executor);
        server.createContext(ROOT,this::handle);
        server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String authority = "127.0.0.1:" + port();
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                    || !authority.equals(exchange.getRequestHeaders().getFirst("Host"))
                    || exchange.getRequestURI().getRawQuery() != null) { respond(exchange,403,""); return; }
            String path = exchange.getRequestURI().getRawPath();
            String method = exchange.getRequestMethod();
            if (method.equals("GET") && path.equals(ROOT)) {
                String token = handoff.transportToken();
                exchange.getResponseHeaders().add("Set-Cookie",COOKIE + "=" + token
                        + "; HttpOnly; SameSite=Strict; Path=" + ROOT + (token.isEmpty() ? "; Max-Age=0" : ""));
                var status = handoff.status();
                String form = status.owner() == HandoffCoordinator.Owner.HUMAN
                        ? "<form method='post' action='/replay-operator/resume'><button>Resume replay</button></form>" : "";
                exchange.getResponseHeaders().set("Content-Type","text/html;charset=UTF-8");
                respond(exchange,200,"<!doctype html><html lang='en'><meta charset='utf-8'><title>Replay operator</title>"
                        + "<h1>Replay operator</h1><p>Owner: " + status.owner() + "</p><p>Reason: " + status.reason()
                        + "</p><p>Step: " + status.step() + "</p><p>Epoch: " + status.epoch()
                        + "</p><p>Repair the existing headed window. Do not submit a reversal.</p>"
                        + form + "<a href='/replay-operator'>Refresh status</a></html>");
            } else if (method.equals("GET") && path.equals(ROOT + "/status")) {
                exchange.getResponseHeaders().set("Content-Type","application/json");
                respond(exchange,200,new ObjectMapper().writeValueAsString(handoff.status()));
            } else if (method.equals("POST") && path.equals(ROOT + "/resume")) {
                if (!("http://" + authority).equals(exchange.getRequestHeaders().getFirst("Origin"))) {
                    respond(exchange,403,""); return;
                }
                String token = null;
                String cookies = exchange.getRequestHeaders().getFirst("Cookie");
                if (cookies != null && cookies.length() <= 512) {
                    for (String pair : cookies.split(";")) {
                        String value = pair.trim();
                        if (value.startsWith(COOKIE + "=")) {
                            if (token != null) { respond(exchange,409,""); return; }
                            token = value.substring(COOKIE.length()+1);
                        }
                    }
                }
                if (token == null || !handoff.resume(token)) { respond(exchange,409,""); return; }
                exchange.getResponseHeaders().add("Set-Cookie",COOKIE + "=; Max-Age=0; HttpOnly; SameSite=Strict; Path=" + ROOT);
                exchange.getResponseHeaders().set("Location",ROOT);
                respond(exchange,303,"");
            } else respond(exchange,404,"");
        } catch (RuntimeException ex) { respond(exchange,500,""); }
        finally { exchange.close(); }
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; form-action 'self'; frame-ancestors 'none'");
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status,bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
