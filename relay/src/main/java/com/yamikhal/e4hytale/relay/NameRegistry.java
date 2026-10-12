package com.yamikhal.e4hytale.relay;

import javax.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

// which name belongs to which host, so a host keeps its address across restarts of the game and of the relay. A host
// is identified by its public key (see HostIdentity), the registry keeps its SHA-256. First come first served, names
// nobody used for `expiry` are freed, retired names too. Kept in one tab-separated file (name, key hash, last use in
// epoch seconds). Not thread-safe, owned by the relay loop
public final class NameRegistry {

    private static final System.Logger LOGGER = System.getLogger("e4hytale-relay");
    private static final HexFormat HEX = HexFormat.of();
    // how often a name in use gets its last-use time written, there is no need to save on every registration
    private static final Duration TOUCH_INTERVAL = Duration.ofHours(1);
    // random two-word picks before falling back to numbered names
    private static final int PLAIN_ATTEMPTS = 20;
    private static final int NUMBERED_ATTEMPTS = 100;
    // key hash of a retired name: taken, but nobody's
    private static final String RETIRED = "retired";

    private final Path file;
    private final Duration expiry;
    private final int capacity;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> byName = new HashMap<>();
    private final Map<String, Entry> byKey = new HashMap<>();
    private boolean dirty;

    // capacity = most names kept at once, new hosts are refused beyond it
    public NameRegistry(Path file, Duration expiry, int capacity) {
        this.file = file;
        this.expiry = expiry;
        this.capacity = capacity;
    }

    public void load(Instant now) throws IOException {
        if (!Files.exists(this.file)) {
            return;
        }

        for (String line : Files.readAllLines(this.file, StandardCharsets.UTF_8)) {
            String[] parts = line.split("\t");
            if (parts.length != 3 || !Names.isWellFormed(parts[0])) continue;

            Entry entry;
            try {
                entry = new Entry(parts[0], parts[1], Instant.ofEpochSecond(Long.parseLong(parts[2])));
            } catch (NumberFormatException e) {
                // a hand-edited or damaged line, the rest of the file is still good
                LOGGER.log(System.Logger.Level.WARNING, "Skipping a damaged line in {0}", this.file);
                continue;
            }

            if (entry.lastUsed().plus(this.expiry).isBefore(now)) {
                this.dirty = true;
                continue;
            }

            this.byName.put(entry.name(), entry);
            if (!entry.keyHash().equals(RETIRED)) {
                this.byKey.put(entry.keyHash(), entry);
            }
        }

        LOGGER.log(System.Logger.Level.INFO, "Loaded {0} host names", this.byName.size());
    }

    // the name already registered to this key hash, null if it has none
    @Nullable
    public String nameOf(byte[] keyHash) {
        Entry entry = this.byKey.get(HEX.formatHex(keyHash));
        return entry == null ? null : entry.name();
    }

    // the name of the host with this key hash, a new random one if it has none yet. Null when the registry is full or
    // no free name turned up
    @Nullable
    public String claim(byte[] keyHash, Instant now) {
        String key = HEX.formatHex(keyHash);
        Entry entry = this.byKey.get(key);
        if (entry != null) {
            if (entry.lastUsed().plus(TOUCH_INTERVAL).isBefore(now)) {
                this.put(new Entry(entry.name(), key, now));
            }

            return entry.name();
        }

        if (this.byName.size() >= this.capacity) {
            return null;
        }

        String name = this.freeName();
        if (name != null) {
            this.put(new Entry(name, key, now));
        }

        return name;
    }

    // detaches the name from this key hash (the host switches to a new key). The name stays taken until it expires,
    // so friends who saved the old address don't end up in a stranger's world soon after
    public void retire(byte[] keyHash, Instant now) {
        Entry entry = this.byKey.remove(HEX.formatHex(keyHash));
        if (entry != null) {
            this.byName.put(entry.name(), new Entry(entry.name(), RETIRED, now));
            this.dirty = true;
        }
    }

    public int size() {
        return this.byName.size();
    }

    // writes the registry if it changed, through a temporary file so a crash never leaves half a file
    public void save() throws IOException {
        if (!this.dirty) {
            return;
        }

        List<String> lines = new ArrayList<>();
        for (Entry entry : this.byName.values()) {
            lines.add(entry.name() + "\t" + entry.keyHash() + "\t" + entry.lastUsed().getEpochSecond());
        }

        Files.createDirectories(this.file.toAbsolutePath().getParent());
        Path temporary = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        this.dirty = false;
    }

    // two words while those are easy to find, numbered names once the plain ones get crowded
    @Nullable
    private String freeName() {
        for (int i = 0; i < PLAIN_ATTEMPTS + NUMBERED_ATTEMPTS; i++) {
            String name = Names.random(this.random, i >= PLAIN_ATTEMPTS);
            if (!this.byName.containsKey(name)) {
                return name;
            }
        }

        return null;
    }

    private void put(Entry entry) {
        this.byName.put(entry.name(), entry);
        this.byKey.put(entry.keyHash(), entry);
        this.dirty = true;
    }

    private record Entry(String name, String keyHash, Instant lastUsed) {}
}
