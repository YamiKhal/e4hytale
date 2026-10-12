package com.yamikhal.e4hytale.relay;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

// a token bucket per source address: `perMinute` events, refilled evenly. Not thread-safe, owned by the relay loop
final class RateLimiter {

    private final double perNano;
    private final double burst;
    private final Map<InetAddress, Bucket> buckets = new HashMap<>();

    RateLimiter(int perMinute) {
        this.perNano = perMinute / 60e9;
        this.burst = perMinute;
    }

    boolean tryAcquire(InetAddress address, long now) {
        Bucket bucket = this.buckets.computeIfAbsent(address, a -> new Bucket(this.burst, now));
        bucket.tokens = Math.min(this.burst, bucket.tokens + (now - bucket.updated) * this.perNano);
        bucket.updated = now;
        if (bucket.tokens < 1.0) return false;

        bucket.tokens -= 1.0;
        return true;
    }

    // forgets full buckets, called now and then so the map doesn't grow with every address ever seen
    void prune(long now) {
        this.buckets.values().removeIf(bucket -> bucket.tokens + (now - bucket.updated) * this.perNano >= this.burst);
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
