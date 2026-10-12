package com.yamikhal.e4hytale;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.yamikhal.e4hytale.tunnel.TunnelClient;

// config.json in the plugin's data folder, which for a hosted world is inside the world's save (one per world). The
// host key is made on first start and is what the relay ties the world's address to, so the address stays the same
// between sessions. It is a private key: never share it, anyone who has it can take the address while you are offline
public final class E4HytaleConfig {

    public static final BuilderCodec<E4HytaleConfig> CODEC = BuilderCodec.builder(E4HytaleConfig.class, E4HytaleConfig::new)
            .append(new KeyedCodec<>("Relay", Codec.STRING), (config, value) -> config.relay = value, config -> config.relay)
            .documentation("Host name of the e4hytale relay. Change it only to use a relay you run yourself.")
            .add()
            .append(new KeyedCodec<>("RelayPort", Codec.INTEGER), (config, value) -> config.relayPort = value,
                    config -> config.relayPort)
            .documentation("UDP port of the relay's host tunnels.")
            .add()
            .append(new KeyedCodec<>("Start", new EnumCodec<>(StartMode.class)), (config, value) -> config.start = value,
                    config -> config.start)
            .documentation("Ask: suggest e4hytale when the world is opened to other players. Always: share right away. "
                    + "Manual: only with /e4h.")
            .add()
            .append(new KeyedCodec<>("CopyAddress", Codec.BOOLEAN), (config, value) -> config.copyAddress = value,
                    config -> config.copyAddress)
            .documentation("Put the address into your clipboard when a hosted world goes online.")
            .add()
            .append(new KeyedCodec<>("HostKey", Codec.STRING), (config, value) -> config.hostKey = value,
                    config -> config.hostKey)
            .documentation("Private key that keeps your address the same. Generated on first start, never share it.")
            .add()
            .build();

    private String relay = "e4h.yamikhal.com";
    private int relayPort = TunnelClient.DEFAULT_PORT;
    private StartMode start = StartMode.ASK;
    private boolean copyAddress = true;
    private String hostKey = "";

    // when sharing starts on its own
    public enum StartMode {
        // a hosted world: a chat hint when the world is opened to other players. A dedicated server: a log line
        ASK,
        // a hosted world: when it is opened to other players. A dedicated server: on boot
        ALWAYS,
        // only through /e4h
        MANUAL
    }

    public String relay() {
        return this.relay;
    }

    public int relayPort() {
        return this.relayPort;
    }

    public StartMode start() {
        return this.start;
    }

    public void setStart(StartMode start) {
        this.start = start;
    }

    public boolean isCopyAddress() {
        return this.copyAddress;
    }

    // hex seed of the host's Ed25519 key (see HostIdentity), empty until generated
    public String hostKey() {
        return this.hostKey;
    }

    public void setHostKey(String hostKey) {
        this.hostKey = hostKey;
    }
}
