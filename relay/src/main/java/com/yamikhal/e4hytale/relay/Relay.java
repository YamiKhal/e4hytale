package com.yamikhal.e4hytale.relay;

import javax.annotation.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

// entry point of the relay app. Configured through E4H_* environment variables (see RelayConfig), stops cleanly on
// SIGTERM so a redeploy tells every host to reconnect instead of letting them time out
public final class Relay {

    private static final System.Logger LOGGER = System.getLogger("e4hytale-relay");
    private static final int MIN_ADMIN_TOKEN_LENGTH = 16;

    private Relay() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        RelayConfig config = RelayConfig.fromEnvironment(System.getenv());
        if (args.length > 0) {
            System.exit(query(config, args[0]));
            return;
        }

        NameRegistry registry = new NameRegistry(config.dataDirectory().resolve("names.tsv"), config.nameExpiry(),
                config.maxNames());
        registry.load(Instant.now());

        Metrics metrics = new Metrics();
        RelayServer server = new RelayServer(config, registry, metrics);
        StatusServer status = config.httpPort() > 0
                ? new StatusServer(config.httpPort(), metrics, server, adminToken())
                : null;
        if (status != null) {
            status.start();
        }

        Thread loop = Thread.ofPlatform().name("e4hytale-relay").start(server::run);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.log(System.Logger.Level.INFO, "Shutting down");
            server.stop();
            if (status != null) {
                status.stop();
            }

            try {
                loop.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        loop.join();
    }

    // E4H_ADMIN_TOKEN, null when unset or blank (docker-compose.yml passes an empty one by default), which keeps
    // POST /broadcast closed. Short tokens are refused, they could be guessed
    @Nullable
    private static String adminToken() {
        String token = System.getenv("E4H_ADMIN_TOKEN");
        if (token == null || token.isBlank()) {
            return null;
        }

        if (token.strip().length() < MIN_ADMIN_TOKEN_LENGTH) {
            LOGGER.log(System.Logger.Level.WARNING, "E4H_ADMIN_TOKEN is shorter than {0} characters, /broadcast stays off",
                    String.valueOf(MIN_ADMIN_TOKEN_LENGTH));
            return null;
        }

        return token.strip();
    }

    // `metrics` or `health` against the running relay's status port, for the container's shell and health check
    // (the image has no curl or wget). Exit code 0 when the relay answered
    private static int query(RelayConfig config, String what) throws InterruptedException {
        if (!what.equals("metrics") && !what.equals("health")) {
            System.err.println("Usage: java -jar relay.jar [metrics | health]");
            return 2;
        }

        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + config.httpPort() + "/" + what))
                    .timeout(Duration.ofSeconds(3)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.print(response.body());
            return response.statusCode() == 200 ? 0 : 1;
        } catch (IOException e) {
            System.err.println("The relay does not answer on port " + config.httpPort() + ": " + e.getMessage());
            return 1;
        }
    }
}
