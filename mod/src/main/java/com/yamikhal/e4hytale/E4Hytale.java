package com.yamikhal.e4hytale;

import com.hypixel.hytale.protocol.io.ServerListener;
import com.hypixel.hytale.protocol.packets.serveraccess.Access;
import com.hypixel.hytale.server.core.Constants;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.io.ServerManager;
import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule;
import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerRequestAccessEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.util.Config;
import com.yamikhal.e4hytale.tunnel.TunnelClient;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

// e4hytale: shares a hosted world (or a dedicated server) through a public relay, so friends can join with Direct
// Connect without any port forwarding. The tunnel goes out from here (see TunnelClient), joiners show up to the
// server as 127.0.0.1 connections. In a hosted world it follows the world's access setting: opening the world to
// friends starts sharing, setting it back to private stops it. One tunnel per server process
public final class E4Hytale extends JavaPlugin {

    // tries to find the game port after boot, the listeners bind asynchronously
    private static final int BIND_ATTEMPTS = 30;

    private final Config<E4HytaleConfig> config = this.withConfig(E4HytaleConfig.CODEC);
    private final Object lock = new Object();
    @Nullable
    private TunnelClient tunnel;

    public E4Hytale(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        this.getCommandRegistry().registerCommand(new E4hCommand(this));
        if (Constants.SINGLEPLAYER) {
            this.getEventRegistry().register(SingleplayerRequestAccessEvent.class, this::onAccessRequested);
        } else {
            this.getEventRegistry().register(BootEvent.class, event -> {
                if (this.config.get().isAutoStart()) this.startWhenBound(BIND_ATTEMPTS);
            });
        }
    }

    @Override
    protected void start() {
        E4HytaleConfig config = this.config.get();
        if (config.hostKey().isEmpty()) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            config.setHostKey(HexFormat.of().formatHex(key));
            this.config.save();
        }
    }

    @Override
    protected void shutdown() {
        this.stopTunnel(false);
    }

    // starts sharing, false if it already runs or the game port isn't bound yet (reported to the host)
    public boolean startTunnel() {
        synchronized (this.lock) {
            if (this.tunnel != null) {
                this.tell(Message.translation("e4hytale.alreadyRunning"));
                return false;
            }

            InetSocketAddress target = gameAddress();
            if (target == null) {
                this.tell(Message.translation("e4hytale.noListener"));
                return false;
            }

            E4HytaleConfig config = this.config.get();
            byte[] key;
            try {
                key = HexFormat.of().parseHex(config.hostKey());
            } catch (IllegalArgumentException e) {
                this.tell(Message.translation("e4hytale.startFailed").param("reason", "HostKey in config.json is not hex"));
                return false;
            }

            TunnelClient tunnel = new TunnelClient(config.relay(), config.relayPort(), key,
                    "e4hytale/" + this.getManifest().getVersion(), target, new Events());
            try {
                tunnel.start();
            } catch (IOException | IllegalArgumentException e) {
                this.getLogger().at(Level.WARNING).withCause(e).log("Could not start the tunnel");
                this.tell(Message.translation("e4hytale.startFailed").param("reason", String.valueOf(e.getMessage())));
                return false;
            }

            this.tunnel = tunnel;
            this.getLogger().at(Level.INFO).log("Connecting to relay %s:%d for %s", config.relay(), config.relayPort(), target);
            this.tell(Message.translation("e4hytale.connecting").param("relay", config.relay()));
            return true;
        }
    }

    // stops sharing, false if it wasn't running. Players who joined through the relay lose their connection
    public boolean stopTunnel(boolean announce) {
        synchronized (this.lock) {
            if (this.tunnel == null) return false;

            this.tunnel.stop();
            this.tunnel = null;
        }

        if (announce) this.tell(Message.translation("e4hytale.stopped"));

        return true;
    }

    @Nullable
    public TunnelClient tunnel() {
        return this.tunnel;
    }

    public String relay() {
        return this.config.get().relay();
    }

    // who may run /e4h: the owner of a hosted world, or anyone with the command's permission on a dedicated server
    public boolean mayControl(@Nullable UUID player, boolean hasPermission) {
        if (Constants.SINGLEPLAYER) return player != null && SingleplayerModule.isOwner(player);

        return hasPermission;
    }

    private void onAccessRequested(SingleplayerRequestAccessEvent event) {
        if (!this.config.get().isAutoStart()) return;

        if (event.getAccess() == Access.Private) {
            this.stopTunnel(true);
        } else if (this.tunnel == null) {
            this.startTunnel();
        }
    }

    // a dedicated server binds its port while booting. Retries once a second until it is there
    private void startWhenBound(int attemptsLeft) {
        if (gameAddress() != null || attemptsLeft <= 0) {
            this.startTunnel();
            return;
        }

        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> this.startWhenBound(attemptsLeft - 1), 1, TimeUnit.SECONDS);
    }

    // the player whose connection comes from this local port, i.e. who joined through that peer link (the server
    // sees every joiner as 127.0.0.1:<port of its link>). Null while the joiner is still logging in
    @Nullable
    PlayerRef playerAt(int localPort) {
        Universe universe = Universe.get();
        if (universe == null || localPort == 0) return null;

        for (PlayerRef player : universe.getPlayers()) {
            if (player.getPacketHandler().getChannel().remoteAddress() instanceof InetSocketAddress remote
                    && remote.getAddress().isLoopbackAddress() && remote.getPort() == localPort) {
                return player;
            }
        }

        return null;
    }

    // messages go to the world's owner in a hosted world and to the log on a dedicated server
    void tell(Message message) {
        if (Constants.SINGLEPLAYER) {
            Universe universe = Universe.get();
            PlayerRef owner = universe == null ? null : universe.getPlayer(SingleplayerModule.getUuid());
            if (owner != null) owner.sendMessage(message);
        }
    }

    // where joiners' datagrams go: the game port on loopback. IPv4 first, a socket bound to one specific address is
    // used as it is
    @Nullable
    static InetSocketAddress gameAddress() {
        ServerManager manager = ServerManager.get();
        if (manager == null) return null;

        InetSocketAddress fallback = null;
        for (ServerListener listener : manager.getListeners()) {
            if (!(listener.localAddress() instanceof InetSocketAddress local)) continue;

            InetAddress address = local.getAddress();
            boolean usable = address.isLoopbackAddress() || address.isAnyLocalAddress();
            if (usable && address instanceof Inet4Address) return new InetSocketAddress(loopback(4), local.getPort());

            if (usable && address instanceof Inet6Address && fallback == null) {
                fallback = new InetSocketAddress(loopback(16), local.getPort());
            } else if (!usable && fallback == null) {
                fallback = local;
            }
        }

        return fallback;
    }

    private static InetAddress loopback(int length) {
        byte[] address = new byte[length];
        address[0] = length == 4 ? (byte) 127 : 0;
        address[length - 1] = 1;
        try {
            return InetAddress.getByAddress(address);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("A 4 or 16 byte address is always valid", e);
        }
    }

    // the tunnel's callbacks, on its threads
    private final class Events implements TunnelClient.Listener {

        @Override
        public void online(String address) {
            E4Hytale.this.getLogger().at(Level.INFO).log("Online, friends can join at %s", address);
            Message shown = Message.raw(address).bold(true).color("#55FF55");
            if (!Constants.SINGLEPLAYER || !E4Hytale.this.config.get().isCopyAddress()) {
                E4Hytale.this.tell(Message.translation("e4hytale.online").param("address", shown));
                return;
            }

            // a hosted world's server runs on the owner's PC, so the address can go straight into their clipboard.
            // Off this thread, the clipboard tool may take a moment
            Thread.ofVirtual().start(() -> E4Hytale.this.tell(Message.translation(Clipboard.copy(address)
                    ? "e4hytale.onlineCopied" : "e4hytale.online").param("address", shown)));
        }

        @Override
        public void offline(String reason, boolean retrying) {
            E4Hytale.this.getLogger().at(Level.WARNING).log("Offline (%s), %s", reason, retrying ? "retrying" : "giving up");
            if (retrying) {
                E4Hytale.this.tell(Message.translation("e4hytale.offlineRetrying").param("reason", reason));
                return;
            }

            synchronized (E4Hytale.this.lock) {
                E4Hytale.this.tunnel = null;
            }

            E4Hytale.this.tell(Message.translation("e4hytale.offline").param("reason", reason));
        }

        @Override
        public void joined(int peer, InetSocketAddress address) {
            E4Hytale.this.getLogger().at(Level.INFO).log("Joiner %d connecting from %s", peer, address);
        }

        @Override
        public void left(int peer) {
            E4Hytale.this.getLogger().at(Level.FINE).log("Joiner %d left", peer);
        }

        @Override
        public void notice(String message) {
            E4Hytale.this.getLogger().at(Level.INFO).log("Relay notice: %s", message);
            E4Hytale.this.tell(Message.translation("e4hytale.notice").param("message", message));
        }
    }
}
