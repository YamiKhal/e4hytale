package com.yamikhal.e4hytale.relay;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

// a token bucket per network (see networkOf): `events` per `period`, refilled evenly, starting full. Not thread-safe,
// owned by the relay loop
final class RateLimiter {

    // networks tracked at once. A flood from spoofed sources would otherwise grow the map until the next prune; past
    // this, unknown networks pass untracked until then (spoofed sources get nothing but a refusal anyway)
    private static final int MAX_BUCKETS = 100_000;

    // tokens per nanosecond
    private final double rate;
    private final double burst;
    private final Map<InetAddress, Bucket> buckets = new HashMap<>();

    RateLimiter(int events, Duration period) {
        this.rate = events / (double) period.toNanos();
        this.burst = events;
    }

    boolean tryAcquire(InetAddress address, long now) {
        InetAddress network = networkOf(address);
        Bucket bucket = this.buckets.get(network);
        if (bucket == null) {
            if (this.buckets.size() >= MAX_BUCKETS) {
                return true;
            }

            bucket = new Bucket(this.burst, now);
            this.buckets.put(network, bucket);
        }

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

    // what limits count by: an IPv4 address on its own, an IPv6 address by its /64. One household or server usually
    // gets a whole /64, so counting single IPv6 addresses would hand everyone billions of fresh ones
    static InetAddress networkOf(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address;
        }

        byte[] bytes = address.getAddress();
        Arrays.fill(bytes, 8, 16, (byte) 0);
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("A 16 byte address is always valid", e);
        }
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
