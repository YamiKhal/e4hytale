package com.yamikhal.e4hytale.relay;

import com.yamikhal.e4hytale.relay.quic.ClientHello;
import com.yamikhal.e4hytale.relay.quic.CryptoStream;
import com.yamikhal.e4hytale.relay.quic.InitialKeys;
import com.yamikhal.e4hytale.relay.quic.QuicPackets;
import com.yamikhal.e4hytale.tunnel.Frame;
import com.yamikhal.e4hytale.tunnel.Frame.Challenge;
import com.yamikhal.e4hytale.tunnel.Frame.Data;
import com.yamikhal.e4hytale.tunnel.Frame.Goodbye;
import com.yamikhal.e4hytale.tunnel.Frame.Hello;
import com.yamikhal.e4hytale.tunnel.Frame.Notice;
import com.yamikhal.e4hytale.tunnel.Frame.PeerClose;
import com.yamikhal.e4hytale.tunnel.Frame.PeerOpen;
import com.yamikhal.e4hytale.tunnel.Frame.Ping;
import com.yamikhal.e4hytale.tunnel.Frame.Pong;
import com.yamikhal.e4hytale.tunnel.Frame.Reason;
import com.yamikhal.e4hytale.tunnel.Frame.Register;
import com.yamikhal.e4hytale.tunnel.Frame.Reject;
import com.yamikhal.e4hytale.tunnel.Frame.Release;
import com.yamikhal.e4hytale.tunnel.Frame.Welcome;
import com.yamikhal.e4hytale.tunnel.FrameCodec;
import com.yamikhal.e4hytale.tunnel.HostIdentity;

import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

// the relay: joiners send QUIC to the game port, hosts keep tunnels open to the tunnel port (see Frame for how they
// register), and every joiner's datagrams are passed to the host whose name was in the joiner's ClientHello (see the
// quic package). The relay never decrypts game traffic past that first message. Everything runs on one thread around
// a selector, so no state here needs locking; other threads talk to it through broadcast and stop
final class RelayServer {

    private static final System.Logger LOGGER = System.getLogger("e4hytale-relay");
    private static final HexFormat HEX = HexFormat.of();
    private static final Duration TIMER_INTERVAL = Duration.ofSeconds(1);
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HOUSEKEEPING_INTERVAL = Duration.ofMinutes(1);
    // joiners waiting for the rest of their ClientHello, more at once are refused
    private static final int MAX_HANDSHAKES = 1024;
    private static final int MAX_HANDSHAKE_DATAGRAMS = 8;
    // largest joiner datagram passed on. Client Initials are padded to 1200 or a bit more, and a joiner's path MTU
    // probes above this would only work through IP fragmentation on the way to the host. 1400 + tunnel header still
    // fits the 1460 byte MTU of DS-Lite links
    private static final int MAX_JOINER_PAYLOAD = 1400;
    // server connection ids remembered per joiner, to find it again when its address changes (NAT rebinding)
    private static final int MAX_CIDS_PER_PEER = 4;
    // registrations per source address and minute (a host needs one, retries after a lost welcome reuse it)
    private static final int REGISTRATIONS_PER_MINUTE = 10;
    // a cookie is accepted in the minute it was made and the next one
    private static final long COOKIE_MINUTE_MILLIS = 60_000;

    private final RelayConfig config;
    private final NameRegistry registry;
    private final Metrics metrics;
    private final SecureRandom random = new SecureRandom();
    // keys the cookies, new on every start so old cookies die with the old process
    private final SecretKeySpec cookieKey;
    private final ByteBuffer in = ByteBuffer.allocateDirect(65_535);
    private final ByteBuffer out = ByteBuffer.allocateDirect(65_535 + Frame.DATA_OVERHEAD);
    private final Map<Long, Host> hosts = new HashMap<>();
    private final Map<String, Host> hostsByName = new HashMap<>();
    private final Map<String, Host> hostsByKey = new HashMap<>();
    private final Map<InetSocketAddress, Peer> peers = new HashMap<>();
    private final Map<ByteBuffer, Peer> peersByCid = new HashMap<>();
    private final Set<Integer> cidLengths = new HashSet<>();
    private final Map<InetSocketAddress, Handshake> handshakes = new HashMap<>();
    private final RateLimiter handshakeLimiter;
    private final RateLimiter registrationLimiter = new RateLimiter(REGISTRATIONS_PER_MINUTE, Duration.ofMinutes(1));
    private final RateLimiter newNameLimiter;
    private final ConcurrentLinkedQueue<String> notices = new ConcurrentLinkedQueue<>();
    private final Selector selector;
    private final DatagramChannel game;
    private final DatagramChannel tunnel;

    private volatile boolean running = true;
    private long lastTimer;
    private long lastHousekeeping;

    RelayServer(RelayConfig config, NameRegistry registry, Metrics metrics) throws IOException {
        this.config = config;
        this.registry = registry;
        this.metrics = metrics;
        byte[] secret = new byte[32];
        this.random.nextBytes(secret);
        this.cookieKey = new SecretKeySpec(secret, "HmacSHA256");
        this.handshakeLimiter = new RateLimiter(config.handshakesPerMinute(), Duration.ofMinutes(1));
        this.newNameLimiter = new RateLimiter(config.newNamesPerDay(), Duration.ofDays(1));
        this.selector = Selector.open();
        this.game = open(config.gamePort());
        this.tunnel = open(config.tunnelPort());
        this.game.register(this.selector, SelectionKey.OP_READ);
        this.tunnel.register(this.selector, SelectionKey.OP_READ);
    }

    // the loop, returns after stop(). Hosts are told the relay goes away so they reconnect as soon as it is back
    void run() {
        LOGGER.log(System.Logger.Level.INFO, "Relay for *.{0} on UDP {1} (joiners) and {2} (hosts)",
                this.config.domain(), String.valueOf(this.config.gamePort()), String.valueOf(this.config.tunnelPort()));
        while (this.running) {
            try {
                this.selector.select(TIMER_INTERVAL.toMillis() / 4);
                Iterator<SelectionKey> keys = this.selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();
                    this.drain((DatagramChannel) key.channel());
                }

                long now = System.nanoTime();
                if (now - this.lastTimer >= TIMER_INTERVAL.toNanos()) {
                    this.lastTimer = now;
                    this.timers(now);
                }

                this.sendNotices();
            } catch (IOException | RuntimeException e) {
                // one bad datagram or a transient socket error must never take the relay down
                LOGGER.log(System.Logger.Level.WARNING, "Relay loop error", e);
            }
        }

        for (Host host : List.copyOf(this.hosts.values())) {
            this.sendTunnel(new Reject(host.session, Reason.SHUTDOWN, "The relay is restarting"), host.address);
        }

        this.saveRegistry();
        try {
            this.game.close();
            this.tunnel.close();
            this.selector.close();
        } catch (IOException e) {
            // shutting down, nothing to recover
            LOGGER.log(System.Logger.Level.DEBUG, "Closing sockets failed", e);
        }
    }

    void stop() {
        this.running = false;
        this.selector.wakeup();
    }

    // a message for every connected host, sent from the loop thread
    void broadcast(String message) {
        this.notices.add(message);
        this.selector.wakeup();
    }

    // the bound ports, which differ from the configured ones when those are 0 (tests)
    int gamePort() throws IOException {
        return ((InetSocketAddress) this.game.getLocalAddress()).getPort();
    }

    int tunnelPort() throws IOException {
        return ((InetSocketAddress) this.tunnel.getLocalAddress()).getPort();
    }

    private void drain(DatagramChannel channel) throws IOException {
        // bounded, so one busy socket can't starve the other or the timers
        for (int i = 0; i < 256; i++) {
            this.in.clear();
            SocketAddress source = channel.receive(this.in);
            if (!(source instanceof InetSocketAddress address)) {
                return;
            }

            this.in.flip();
            this.metrics.bytesIn.addAndGet(this.in.remaining());
            long now = System.nanoTime();
            if (channel == this.game) {
                this.onJoinerDatagram(address, this.in, now);
            } else {
                this.onTunnelDatagram(address, this.in, now);
            }
        }
    }

    private void onJoinerDatagram(InetSocketAddress source, ByteBuffer datagram, long now) {
        Peer peer = this.peers.get(source);
        if (peer != null) {
            if (datagram.remaining() <= MAX_JOINER_PAYLOAD) {
                this.forwardToHost(peer, datagram, now);
            }

            return;
        }

        if (!QuicPackets.isLongHeader(datagram)) {
            this.rebind(source, datagram, now);
        } else if (QuicPackets.version(datagram) == InitialKeys.VERSION_1) {
            this.handshake(source, datagram, now);
        }
    }

    // collects a joiner's Initial packets until its ClientHello is complete, then routes it by the server name
    private void handshake(InetSocketAddress source, ByteBuffer datagram, long now) {
        Handshake handshake = this.handshakes.get(source);
        if (handshake == null) {
            if (this.handshakes.size() >= MAX_HANDSHAKES || !this.handshakeLimiter.tryAcquire(source.getAddress(), now)) {
                this.metrics.refused.incrementAndGet();
                return;
            }

            handshake = new Handshake(source, now);
            this.handshakes.put(source, handshake);
        }

        if (handshake.datagrams.size() >= MAX_HANDSHAKE_DATAGRAMS) {
            this.handshakes.remove(source);
            return;
        }

        byte[] copy = new byte[datagram.remaining()];
        datagram.duplicate().get(copy);
        handshake.datagrams.add(copy);
        for (QuicPackets.Initial initial : QuicPackets.openClientInitials(datagram, handshake::keysFor)) {
            handshake.scid = initial.scid();
            if (!QuicPackets.readCryptoFrames(initial.payload(), handshake.stream)) {
                this.handshakes.remove(source);
                return;
            }
        }

        switch (ClientHello.serverName(handshake.stream.prefix())) {
            case ClientHello.Incomplete incomplete -> {}
            case ClientHello.Missing missing -> {
                this.handshakes.remove(source);
                this.refuse(handshake, "Connect with an e4hytale address like name." + this.config.domain());
            }
            case ClientHello.Found found -> {
                this.handshakes.remove(source);
                this.route(handshake, found.serverName(), now);
            }
        }
    }

    private void route(Handshake handshake, String serverName, long now) {
        String label = this.labelOf(serverName);
        Host host = label == null ? null : this.hostsByName.get(label);
        if (host == null) {
            this.metrics.refused.incrementAndGet();
            this.refuse(handshake, label == null
                    ? "This relay only serves *." + this.config.domain() + " addresses"
                    : "Nobody is hosting " + serverName + " right now");
            return;
        }

        if (host.peers.size() >= this.config.maxPeersPerHost()) {
            this.metrics.refused.incrementAndGet();
            this.refuse(handshake, serverName + " is full");
            return;
        }

        Peer peer = new Peer(host.nextPeer++, host, handshake.address, now);
        host.peers.put(peer.id, peer);
        this.peers.put(peer.address, peer);
        this.metrics.joins.incrementAndGet();
        this.sendTunnel(new PeerOpen(host.session, peer.id, peer.address), host.address);
        for (byte[] buffered : handshake.datagrams) {
            this.forwardToHost(peer, ByteBuffer.wrap(buffered), now);
        }
    }

    // the host-name label of a server name under our domain, null for any other name
    @Nullable
    private String labelOf(String serverName) {
        String suffix = "." + this.config.domain();
        if (!serverName.endsWith(suffix)) {
            return null;
        }

        String label = serverName.substring(0, serverName.length() - suffix.length());
        return Names.isWellFormed(label) ? label : null;
    }

    // tells the joiner's client why it can't connect, in a CONNECTION_CLOSE it can decrypt. Much smaller than the
    // client's padded Initial, so a spoofed source can't use it for amplification
    private void refuse(Handshake handshake, String reason) {
        if (handshake.dcid == null || handshake.scid == null) {
            return;
        }

        byte[] serverScid = new byte[8];
        this.random.nextBytes(serverScid);
        this.send(this.game, QuicPackets.refusal(handshake.dcid, handshake.scid, serverScid, reason), handshake.address);
    }

    // a short header packet from an unknown address: a known joiner whose NAT gave it a new port. Found by the
    // server's connection id, which the relay saw in the server's long header packets
    private void rebind(InetSocketAddress source, ByteBuffer datagram, long now) {
        for (int length : this.cidLengths) {
            byte[] cid = QuicPackets.shortHeaderDcid(datagram, length);
            Peer peer = cid == null ? null : this.peersByCid.get(ByteBuffer.wrap(cid));
            if (peer == null) continue;

            this.peers.remove(peer.address);
            peer.address = source;
            this.peers.put(source, peer);
            this.sendTunnel(new PeerOpen(peer.host.session, peer.id, source), peer.host.address);
            this.forwardToHost(peer, datagram, now);
            return;
        }
    }

    private void forwardToHost(Peer peer, ByteBuffer datagram, long now) {
        peer.lastSeen = now;
        FrameCodec.encodeData(peer.host.session, peer.id, datagram, this.out);
        this.send(this.tunnel, this.out, peer.host.address);
    }

    private void onTunnelDatagram(InetSocketAddress source, ByteBuffer datagram, long now) {
        // replies are never larger than what they answer (a spoofed source must not turn the relay into an amplifier):
        // hellos and registrations are padded, rejects of unknown sessions carry no message
        int size = datagram.remaining();
        int version = FrameCodec.versionOf(datagram);
        if (version != Frame.VERSION) {
            // noise, or a host of another version: a reply in this relay's version lets that host report the mismatch
            if (version >= 0 && size >= Frame.PADDED_SIZE) {
                this.sendTunnel(new Reject(0, Reason.BAD_REQUEST, "Tunnel version mismatch"), source);
            }

            return;
        }

        Frame frame = FrameCodec.decode(datagram);
        switch (frame) {
            case null -> {}
            case Hello hello when size >= Frame.PADDED_SIZE ->
                    this.sendTunnel(new Challenge(hello.nonce(), this.cookie(source, hello.nonce(), 0)), source);
            case Hello hello -> {}
            case Register register -> this.register(source, register, now);
            default -> this.onSessionFrame(source, frame, now);
        }
    }

    private void onSessionFrame(InetSocketAddress source, Frame frame, long now) {
        long session = sessionOf(frame);
        Host host = this.hosts.get(session);
        if (host == null) {
            this.sendTunnel(new Reject(session, Reason.UNKNOWN_SESSION, ""), source);
            return;
        }

        // the session id is the host's proof, so a host whose NAT moved it to a new port just carries on
        host.address = source;
        host.lastSeen = now;
        switch (frame) {
            case Data data -> this.forwardToJoiner(host, data, now);
            case PeerClose close -> {
                Peer peer = host.peers.get(close.peer());
                if (peer != null) {
                    this.removePeer(peer, false);
                }
            }
            case Ping ping -> this.sendTunnel(new Pong(host.session, ping.nonce()), source);
            case Goodbye goodbye -> this.closeHost(host, "said goodbye");
            case Release release -> {
                this.registry.retire(HEX.parseHex(host.key), Instant.now());
                this.closeHost(host, "retired its name");
            }
            default -> {}
        }
    }

    private void forwardToJoiner(Host host, Data data, long now) {
        Peer peer = host.peers.get(data.peer());
        if (peer == null) {
            // the joiner is gone here but the host still sends, tell it to close its side
            this.sendTunnel(new PeerClose(host.session, data.peer()), host.address);
            return;
        }

        ByteBuffer payload = data.payload();
        if (payload.remaining() > this.config.maxPayload()) {
            this.metrics.dropped.incrementAndGet();
            return;
        }

        peer.lastSeen = now;
        if (peer.cids.size() < MAX_CIDS_PER_PEER) {
            this.learnCid(peer, payload);
        }

        this.send(this.game, payload, peer.address);
    }

    private void learnCid(Peer peer, ByteBuffer payload) {
        byte[] cid = QuicPackets.longHeaderScid(payload);
        if (cid == null || peer.cids.contains(ByteBuffer.wrap(cid))) {
            return;
        }

        ByteBuffer key = ByteBuffer.wrap(cid);
        peer.cids.add(key);
        this.peersByCid.put(key, peer);
        this.cidLengths.add(cid.length);
    }

    // a host proving its identity. The cookie shows the registration comes from the address the challenge went to
    // (no spoofed sources), the signature that the host owns the key its name is tied to
    private void register(InetSocketAddress source, Register register, long now) {
        if (!this.validCookie(source, register.nonce(), register.cookie())) {
            return;
        }

        byte[] keyHash = sha256(register.publicKey());
        String key = HEX.formatHex(keyHash);
        Host existing = this.hostsByKey.get(key);
        if (existing != null && existing.address.equals(source)) {
            // our welcome got lost, send it again for this attempt
            existing.lastSeen = now;
            this.welcome(existing, register.nonce());
            return;
        }

        if (!this.registrationLimiter.tryAcquire(source.getAddress(), now)
                || !HostIdentity.verify(register.publicKey(), register.nonce(), register.cookie(), register.signature())) {
            return;
        }

        String refusal = this.admit(source, keyHash, existing, now);
        if (refusal != null) {
            this.sendTunnel(new Reject(register.nonce(), Reason.LIMIT, refusal), source);
            return;
        }

        String name = this.registry.claim(keyHash, Instant.now());
        if (name == null) {
            this.sendTunnel(new Reject(register.nonce(), Reason.LIMIT, "The relay has no free names, try later"), source);
            return;
        }

        if (existing != null) {
            // the same host from a new socket: the game restarted the tunnel, the old session is dead
            this.closeHost(existing, "reconnected");
        }

        Host host = new Host(this.newSession(), name, key, source, now);
        this.hosts.put(host.session, host);
        this.hostsByName.put(name, host);
        this.hostsByKey.put(key, host);
        this.metrics.hosts.set(this.hosts.size());
        LOGGER.log(System.Logger.Level.INFO, "Host {0} online ({1})", name, register.software());
        this.welcome(host, register.nonce());
    }

    // why a verified host can't get a session, null if it can
    @Nullable
    private String admit(InetSocketAddress source, byte[] keyHash, @Nullable Host existing, long now) {
        if (existing == null && this.hosts.size() >= this.config.maxHosts()) {
            return "The relay is full, try again later";
        }

        long fromAddress = this.hosts.values().stream()
                .filter(host -> host != existing && host.address.getAddress().equals(source.getAddress()))
                .count();
        if (fromAddress >= this.config.maxHostsPerAddress()) {
            return "Too many worlds hosted from your network";
        }

        if (this.registry.nameOf(keyHash) == null && !this.newNameLimiter.tryAcquire(source.getAddress(), now)) {
            return "Too many new e4hytale addresses from your network today";
        }

        return null;
    }

    private void welcome(Host host, long nonce) {
        this.sendTunnel(new Welcome(nonce, host.session, host.name + "." + this.config.domain(),
                (int) this.config.keepalive().toSeconds(), this.config.maxPayload()), host.address);
    }

    // HMAC of the source address, nonce and minute: proves a registration answers a challenge sent to that address,
    // without keeping state per hello
    private byte[] cookie(InetSocketAddress source, long nonce, int minutesAgo) {
        long minute = System.currentTimeMillis() / COOKIE_MINUTE_MILLIS - minutesAgo;
        byte[] address = source.getAddress().getAddress();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(this.cookieKey);
            mac.update(ByteBuffer.allocate(address.length + Integer.BYTES + 2 * Long.BYTES).put(address)
                    .putInt(source.getPort()).putLong(nonce).putLong(minute).array());
            return Arrays.copyOf(mac.doFinal(), Frame.COOKIE_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private boolean validCookie(InetSocketAddress source, long nonce, byte[] cookie) {
        return MessageDigest.isEqual(cookie, this.cookie(source, nonce, 0))
                || MessageDigest.isEqual(cookie, this.cookie(source, nonce, 1));
    }

    private void timers(long now) {
        long hostTimeout = this.config.keepalive().toNanos() * 3;
        for (Host host : List.copyOf(this.hosts.values())) {
            if (now - host.lastSeen > hostTimeout) {
                this.closeHost(host, "timed out");
            }
        }

        long peerTimeout = this.config.peerIdleTimeout().toNanos();
        for (Peer peer : List.copyOf(this.peers.values())) {
            if (now - peer.lastSeen > peerTimeout) {
                this.removePeer(peer, true);
            }
        }

        this.handshakes.values().removeIf(handshake -> now - handshake.started > HANDSHAKE_TIMEOUT.toNanos());
        if (now - this.lastHousekeeping > HOUSEKEEPING_INTERVAL.toNanos()) {
            this.lastHousekeeping = now;
            this.handshakeLimiter.prune(now);
            this.registrationLimiter.prune(now);
            this.newNameLimiter.prune(now);
            this.saveRegistry();
        }

        this.metrics.peers.set(this.peers.size());
        this.metrics.names.set(this.registry.size());
    }

    private void sendNotices() {
        String notice = this.notices.poll();
        while (notice != null) {
            for (Host host : this.hosts.values()) {
                this.sendTunnel(new Notice(host.session, notice), host.address);
            }

            notice = this.notices.poll();
        }
    }

    private void closeHost(Host host, String why) {
        this.hosts.remove(host.session);
        this.hostsByName.remove(host.name, host);
        this.hostsByKey.remove(host.key, host);
        for (Peer peer : List.copyOf(host.peers.values())) {
            this.removePeer(peer, false);
        }

        this.metrics.hosts.set(this.hosts.size());
        LOGGER.log(System.Logger.Level.INFO, "Host {0} offline ({1})", host.name, why);
    }

    private void removePeer(Peer peer, boolean tellHost) {
        peer.host.peers.remove(peer.id);
        this.peers.remove(peer.address, peer);
        for (ByteBuffer cid : peer.cids) {
            this.peersByCid.remove(cid, peer);
        }

        if (tellHost) {
            this.sendTunnel(new PeerClose(peer.host.session, peer.id), peer.host.address);
        }
    }

    // random and never 0, which stands for "no session" on the wire
    private long newSession() {
        long session = this.random.nextLong();
        while (session == 0 || this.hosts.containsKey(session)) {
            session = this.random.nextLong();
        }

        return session;
    }

    private void saveRegistry() {
        try {
            this.registry.save();
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not save the host names", e);
        }
    }

    private void sendTunnel(Frame frame, InetSocketAddress target) {
        FrameCodec.encode(frame, this.out);
        this.send(this.tunnel, this.out, target);
    }

    private void send(DatagramChannel channel, ByteBuffer datagram, InetSocketAddress target) {
        try {
            int size = datagram.remaining();
            channel.send(datagram, target);
            this.metrics.bytesOut.addAndGet(size);
        } catch (IOException e) {
            // unreachable target or a full socket buffer: UDP may drop, QUIC on both ends retransmits
            LOGGER.log(System.Logger.Level.DEBUG, "Send to {0} failed: {1}", target, e.getMessage());
        }
    }

    private static DatagramChannel open(int port) throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        channel.setOption(StandardSocketOptions.SO_RCVBUF, 4 * 1024 * 1024);
        channel.setOption(StandardSocketOptions.SO_SNDBUF, 4 * 1024 * 1024);
        channel.bind(new InetSocketAddress(port));
        channel.configureBlocking(false);
        return channel;
    }

    // 0 for frames without a session
    private static long sessionOf(Frame frame) {
        return switch (frame) {
            case Data data -> data.session();
            case PeerClose close -> close.session();
            case Ping ping -> ping.session();
            case Goodbye goodbye -> goodbye.session();
            case Release release -> release.session();
            default -> 0;
        };
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static final class Host {

        private final long session;
        private final String name;
        private final String key;
        private final Map<Integer, Peer> peers = new HashMap<>();
        private InetSocketAddress address;
        private long lastSeen;
        private int nextPeer = 1;

        private Host(long session, String name, String key, InetSocketAddress address, long lastSeen) {
            this.session = session;
            this.name = name;
            this.key = key;
            this.address = address;
            this.lastSeen = lastSeen;
        }
    }

    private static final class Peer {

        private final int id;
        private final Host host;
        private final List<ByteBuffer> cids = new ArrayList<>();
        private InetSocketAddress address;
        private long lastSeen;

        private Peer(int id, Host host, InetSocketAddress address, long lastSeen) {
            this.id = id;
            this.host = host;
            this.address = address;
            this.lastSeen = lastSeen;
        }
    }

    private static final class Handshake {

        private final InetSocketAddress address;
        private final long started;
        private final CryptoStream stream = new CryptoStream();
        private final List<byte[]> datagrams = new ArrayList<>();
        @Nullable
        private byte[] dcid;
        @Nullable
        private byte[] scid;
        @Nullable
        private InitialKeys keys;

        private Handshake(InetSocketAddress address, long started) {
            this.address = address;
            this.started = started;
        }

        // the client keeps its first dcid for every Initial until the server answers, so one derivation is enough
        private InitialKeys keysFor(byte[] dcid) {
            if (this.keys == null || !Arrays.equals(this.dcid, dcid)) {
                this.dcid = dcid;
                this.keys = InitialKeys.client(dcid);
            }

            return this.keys;
        }
    }
}
