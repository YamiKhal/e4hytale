package com.yamikhal.e4hytale;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.yamikhal.e4hytale.tunnel.TunnelClient;

// config.json in the plugin's data folder. The host key is made on first start and identifies this install to the
// relay, which is why the address stays the same between sessions: keep it secret, anyone with it can take the
// address while you are offline
public class E4HytaleConfig {

    public static final BuilderCodec<E4HytaleConfig> CODEC = BuilderCodec.builder(E4HytaleConfig.class, E4HytaleConfig::new)
            .append(new KeyedCodec<>("Relay", Codec.STRING), (config, value) -> config.relay = value, config -> config.relay)
            .documentation("Host name of the e4hytale relay. Change it only to use a relay you run yourself.")
            .add()
            .append(new KeyedCodec<>("RelayPort", Codec.INTEGER), (config, value) -> config.relayPort = value,
                    config -> config.relayPort)
            .documentation("UDP port of the relay's host tunnels.")
            .add()
            .append(new KeyedCodec<>("AutoStart", Codec.BOOLEAN), (config, value) -> config.autoStart = value,
                    config -> config.autoStart)
            .documentation("Start sharing when a hosted world is opened to other players, or when a dedicated server starts.")
            .add()
            .append(new KeyedCodec<>("HostKey", Codec.STRING), (config, value) -> config.hostKey = value,
                    config -> config.hostKey)
            .documentation("Secret that keeps your address the same. Generated on first start, never share it.")
            .add()
            .build();

    private String relay = "e4h.yamikhal.com";
    private int relayPort = TunnelClient.DEFAULT_PORT;
    private boolean autoStart = true;
    private String hostKey = "";

    public String relay() {
        return this.relay;
    }

    public int relayPort() {
        return this.relayPort;
    }

    public boolean isAutoStart() {
        return this.autoStart;
    }

    // hex, empty until generated
    public String hostKey() {
        return this.hostKey;
    }

    public void setHostKey(String hostKey) {
        this.hostKey = hostKey;
    }
}
