package com.yamikhal.e4hytale.relay;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void ipv6AddressesOfOneSlash64ShareTheirLimit() throws Exception {
        RateLimiter limiter = new RateLimiter(2, Duration.ofDays(1));
        assertTrue(limiter.tryAcquire(InetAddress.getByName("2001:db8:1:2::1"), 0));
        assertTrue(limiter.tryAcquire(InetAddress.getByName("2001:db8:1:2:aaaa:bbbb:cccc:dddd"), 0));
        assertFalse(limiter.tryAcquire(InetAddress.getByName("2001:db8:1:2::ffff"), 0));

        // the next /64 is someone else
        assertTrue(limiter.tryAcquire(InetAddress.getByName("2001:db8:1:3::1"), 0));
    }

    @Test
    void ipv4AddressesCountOnTheirOwn() throws Exception {
        InetAddress first = InetAddress.getByName("192.0.2.1");
        assertEquals(first, RateLimiter.networkOf(first));
        assertNotEquals(RateLimiter.networkOf(first), RateLimiter.networkOf(InetAddress.getByName("192.0.2.2")));
    }

    @Test
    void refillsOverThePeriod() throws Exception {
        RateLimiter limiter = new RateLimiter(1, Duration.ofSeconds(1));
        InetAddress address = InetAddress.getByName("192.0.2.1");
        assertTrue(limiter.tryAcquire(address, 0));
        assertFalse(limiter.tryAcquire(address, Duration.ofMillis(500).toNanos()));
        assertTrue(limiter.tryAcquire(address, Duration.ofMillis(1001).toNanos()));
    }
}
