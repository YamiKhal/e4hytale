package com.yamikhal.e4hytale.relay;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

// a token bucket per source address: `events` per `period`, refilled evenly, starting full. Not thread-safe, owned by
// the relay loop
final class RateLimiter {

    // tokens per nanosecond
    private final double rate;
    private final double burst;
    private final Map<InetAddress, Bucket> buckets = new HashMap<>();

    RateLimiter(int events, Duration period) {
        this.rate = events / (double) period.toNanos();
        this.burst = events;
    }

    boolean tryAcquire(InetAddress address, long now) {
        Bucket bucket = this.buckets.computeIfAbsent(address, key -> new Bucket(this.burst, now));
        bucket.tokens = Math.min(this.burst, bucket.tokens + (now - bucket.updated) * this.rate);
        bucket.updated = now;
        if (bucket.tokens < 1.0) {
            return false;
        }

        bucket.tokens -= 1.0;
        return true;
    }

    // forgets buckets that refilled completely, called now and then so the map doesn't grow with every address seen
    void prune(long now) {
        this.buckets.values().removeIf(bucket -> bucket.tokens + (now - bucket.updated) * this.rate >= this.burst);
    }

    private static final class Bucket {

        private double tokens;
        private long updated;

        private Bucket(double tokens, long updated) {
            this.tokens = tokens;
            this.updated = updated;
        }
    }
}
