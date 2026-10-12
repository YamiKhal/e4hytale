package com.yamikhal.e4hytale.relay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameRegistryTest {

    @TempDir
    Path data;

    @Test
    void keepsAHostsNameAcrossRestarts() throws Exception {
        Path file = this.data.resolve("names.tsv");
        NameRegistry registry = new NameRegistry(file, Duration.ofDays(90), 100);
        String name = registry.claim(key(1), Instant.now());
        registry.save();

        NameRegistry reloaded = new NameRegistry(file, Duration.ofDays(90), 100);
        reloaded.load(Instant.now());
        assertEquals(name, reloaded.nameOf(key(1)));
        assertEquals(name, reloaded.claim(key(1), Instant.now()));
    }

    @Test
    void handsOutNumberedNamesOnceThePlainOnesRunShort() {
        NameRegistry registry = new NameRegistry(this.data.resolve("names.tsv"), Duration.ofDays(90), 20_000);
        Set<String> names = new HashSet<>();
        boolean numbered = false;
        for (int i = 0; i < 15_000; i++) {
            String name = registry.claim(key(i), Instant.now());
            assertNotNull(name);
            assertTrue(Names.isWellFormed(name), name);
            assertTrue(names.add(name), "handed out twice: " + name);
            numbered |= name.chars().filter(c -> c == '-').count() == 2;
        }

        assertTrue(numbered);
    }

    @Test
    void refusesNewHostsWhenFullButKeepsKnownOnes() {
        NameRegistry registry = new NameRegistry(this.data.resolve("names.tsv"), Duration.ofDays(90), 2);
        String first = registry.claim(key(1), Instant.now());
        registry.claim(key(2), Instant.now());
        assertNull(registry.claim(key(3), Instant.now()));
        assertEquals(first, registry.claim(key(1), Instant.now()));
    }

    @Test
    void forgetsUnusedNamesAndDamagedLines() throws Exception {
        Path file = this.data.resolve("names.tsv");
        long old = Instant.now().minus(Duration.ofDays(100)).getEpochSecond();
        long recent = Instant.now().getEpochSecond();
        Files.writeString(file, "old-name\taa\t" + old + "\nfresh-name\tbb\t" + recent + "\nbroken\nbad-line\tcc\tsoon\n");
        NameRegistry registry = new NameRegistry(file, Duration.ofDays(90), 100);
        registry.load(Instant.now());
        assertEquals(1, registry.size());
    }

    @Test
    void retiredNamesStayTakenAcrossRestartsButBelongToNobody() throws Exception {
        Path file = this.data.resolve("names.tsv");
        NameRegistry registry = new NameRegistry(file, Duration.ofDays(90), 100);
        String old = registry.claim(key(1), Instant.now());
        registry.retire(key(1), Instant.now());
        assertNull(registry.nameOf(key(1)));
        String fresh = registry.claim(key(1), Instant.now());
        assertNotNull(fresh);
        assertFalse(fresh.equals(old));
        registry.save();

        NameRegistry reloaded = new NameRegistry(file, Duration.ofDays(90), 100);
        reloaded.load(Instant.now());
        assertEquals(2, reloaded.size());
        assertEquals(fresh, reloaded.nameOf(key(1)));
    }

    @Test
    void acceptsOnlyNamesItCouldHaveMade() {
        assertTrue(Names.isWellFormed("mossy-kweebec"));
        assertTrue(Names.isWellFormed("mossy-kweebec-417"));
        assertFalse(Names.isWellFormed("relay"));
        assertFalse(Names.isWellFormed("a-b-c"));
        assertFalse(Names.isWellFormed("mossy-kweebec-4170"));
        assertFalse(Names.isWellFormed("Mossy-kweebec"));
    }

    private static byte[] key(int value) {
        return ByteBuffer.allocate(32).putInt(value).array();
    }
}
