package com.yamikhal.e4hytale.relay;

import java.io.IOException;
import java.time.Instant;

// entry point of the relay app. Configured through E4H_* environment variables (see RelayConfig), stops cleanly on
// SIGTERM so a redeploy tells every host to reconnect instead of letting them time out
public final class Relay {

    private static final System.Logger LOGGER = System.getLogger("e4hytale-relay");

    private Relay() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        RelayConfig config = RelayConfig.fromEnvironment(System.getenv());
        NameRegistry registry = new NameRegistry(config.dataDirectory().resolve("names.tsv"), config.nameExpiry());
        registry.load(Instant.now());

        Metrics metrics = new Metrics();
        RelayServer server = new RelayServer(config, registry, metrics);
        StatusServer status = config.httpPort() > 0
                ? new StatusServer(config.httpPort(), metrics, server, System.getenv("E4H_ADMIN_TOKEN"))
                : null;
        if (status != null) status.start();

        Thread loop = Thread.ofPlatform().name("e4hytale-relay").start(server::run);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.log(System.Logger.Level.INFO, "Shutting down");
            server.stop();
            if (status != null) status.stop();

            try {
                loop.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        loop.join();
    }
}
