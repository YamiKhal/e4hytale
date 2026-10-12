package com.yamikhal.e4hytale.relay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;

// a small HTTP side door: GET /health for the container platform's health check, GET /metrics for counters, and
// POST /broadcast (body = message) to show every host a message, only if E4H_ADMIN_TOKEN is set and sent as a bearer
// token. Runs on its own virtual threads
final class StatusServer {

    private final HttpServer server;

    StatusServer(int port, Metrics metrics, RelayServer relay, @Nullable String adminToken) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/health", exchange -> respond(exchange, 200, "ok\n"));
        this.server.createContext("/metrics", exchange -> respond(exchange, 200, metrics.render()));
        this.server.createContext("/broadcast", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 405, "POST only\n");
                return;
            }

            if (adminToken == null || !authorized(exchange, adminToken)) {
                respond(exchange, 403, "forbidden\n");
                return;
            }

            String message = new String(exchange.getRequestBody().readNBytes(1024), StandardCharsets.UTF_8).trim();
            relay.broadcast(message);
            respond(exchange, 200, "sent\n");
        });
    }

    void start() {
        this.server.start();
    }

    void stop() {
        this.server.stop(0);
    }

    private static boolean authorized(HttpExchange exchange, String token) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null) return false;

        // constant time, so the token can't be guessed byte by byte from response times
        return MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
