package com.yamikhal.e4hytale.relay;

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

// which name belongs to which host, so a host keeps its address across restarts of the game and of the relay. Hosts
// prove ownership with their host key, the registry only keeps its SHA-256. First come first served, names nobody
// used for `expiry` are freed. Kept in one tab-separated file (name, key hash, last use in epoch seconds). Not
// thread-safe, owned by the relay loop
public final class NameRegistry {

    private static final System.Logger LOGGER = System.getLogger("e4hytale-relay");
    private static final HexFormat HEX = HexFormat.of();
    // how often a name in use gets its last-use time written, there is no need to save on every hello
    private static final Duration TOUCH_INTERVAL = Duration.ofHours(1);

    private final Path file;
    private final Duration expiry;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> byName = new HashMap<>();
    private final Map<String, Entry> byKey = new HashMap<>();
    private boolean dirty;

    public NameRegistry(Path file, Duration expiry) {
        this.file = file;
        this.expiry = expiry;
    }

    public void load(Instant now) throws IOException {
        if (!Files.exists(this.file)) return;

        for (String line : Files.readAllLines(this.file, StandardCharsets.UTF_8)) {
            String[] parts = line.split("\t");
            if (parts.length != 3) continue;

            Entry entry = new Entry(parts[0], parts[1], Instant.ofEpochSecond(Long.parseLong(parts[2])));
            if (entry.lastUsed().plus(this.expiry).isBefore(now)) {
                this.dirty = true;
                continue;
            }

            this.byName.put(entry.name(), entry);
            this.byKey.put(entry.keyHash(), entry);
        }

        LOGGER.log(System.Logger.Level.INFO, "Loaded {0} host names", this.byName.size());
    }

    // the name of the host with this key hash, a new random one if it has none yet
    public String claim(byte[] keyHash, Instant now) {
        String key = HEX.formatHex(keyHash);
        Entry entry = this.byKey.get(key);
        if (entry != null) {
            if (entry.lastUsed().plus(TOUCH_INTERVAL).isBefore(now)) this.put(new Entry(entry.name(), key, now));

            return entry.name();
        }

        String name = Names.random(this.random);
        while (this.byName.containsKey(name)) {
            name = Names.random(this.random);
        }

        this.put(new Entry(name, key, now));
        return name;
    }

    public int size() {
        return this.byName.size();
    }

    // writes the registry if it changed, through a temporary file so a crash never leaves half a file
    public void save() throws IOException {
        if (!this.dirty) return;

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

    private void put(Entry entry) {
        this.byName.put(entry.name(), entry);
        this.byKey.put(entry.keyHash(), entry);
        this.dirty = true;
    }

    private record Entry(String name, String keyHash, Instant lastUsed) {}
}
