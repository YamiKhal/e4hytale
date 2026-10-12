package com.yamikhal.e4hytale.relay;

import com.yamikhal.e4hytale.tunnel.TunnelClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

// the relay's settings, read from environment variables so a container platform (Coolify) can set them
public record RelayConfig(
        // joiners connect to <name>.<domain>, which needs a wildcard DNS record pointing at the relay
        String domain,
        // UDP port for joiners, Hytale's default so addresses need no port
        int gamePort,
        // UDP port for host tunnels
        int tunnelPort,
        // TCP port for /health and /metrics, 0 to disable
        int httpPort,
        // where the name registry is kept, mount it as a volume so addresses survive redeploys
        Path dataDirectory,
        int maxHosts,
        // names the registry keeps at once, new hosts are refused beyond it
        int maxNames,
        // new names one source address may register per day, so nobody can claim all the good ones
        int newNamesPerDay,
        int maxHostsPerAddress,
        int maxPeersPerHost,
        // new joiner handshakes per source address and minute
        int handshakesPerMinute,
        // game datagrams larger than this are dropped. Hosts get it in the welcome and drop the same way
        int maxPayload,
        Duration keepalive,
        Duration peerIdleTimeout,
        // a registered name nobody used for this long is free again
        Duration nameExpiry) {

    public static RelayConfig fromEnvironment(Map<String, String> env) {
        return new RelayConfig(
                env.getOrDefault("E4H_DOMAIN", "e4h.yamikhal.com").toLowerCase(Locale.ROOT),
                integer(env, "E4H_GAME_PORT", 5520),
                integer(env, "E4H_TUNNEL_PORT", TunnelClient.DEFAULT_PORT),
                integer(env, "E4H_HTTP_PORT", 8080),
                Path.of(env.getOrDefault("E4H_DATA", "data")),
                integer(env, "E4H_MAX_HOSTS", 500),
                integer(env, "E4H_MAX_NAMES", 100_000),
                integer(env, "E4H_NEW_NAMES_PER_DAY", 10),
                integer(env, "E4H_MAX_HOSTS_PER_ADDRESS", 4),
                integer(env, "E4H_MAX_PEERS_PER_HOST", 32),
                integer(env, "E4H_HANDSHAKES_PER_MINUTE", 30),
                integer(env, "E4H_MAX_PAYLOAD", 1200),
                Duration.ofSeconds(integer(env, "E4H_KEEPALIVE_SECONDS", 15)),
                Duration.ofSeconds(integer(env, "E4H_PEER_IDLE_SECONDS", 60)),
                Duration.ofDays(integer(env, "E4H_NAME_EXPIRY_DAYS", 90)));
    }

    private static int integer(Map<String, String> env, String key, int fallback) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number, got '" + value + "'", e);
        }
    }
}
