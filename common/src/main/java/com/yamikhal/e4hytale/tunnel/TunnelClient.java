package com.yamikhal.e4hytale.tunnel;

import com.yamikhal.e4hytale.tunnel.Frame.Data;
import com.yamikhal.e4hytale.tunnel.Frame.Goodbye;
import com.yamikhal.e4hytale.tunnel.Frame.Hello;
import com.yamikhal.e4hytale.tunnel.Frame.Notice;
import com.yamikhal.e4hytale.tunnel.Frame.PeerClose;
import com.yamikhal.e4hytale.tunnel.Frame.PeerOpen;
import com.yamikhal.e4hytale.tunnel.Frame.Ping;
import com.yamikhal.e4hytale.tunnel.Frame.Pong;
import com.yamikhal.e4hytale.tunnel.Frame.Reject;
import com.yamikhal.e4hytale.tunnel.Frame.Welcome;

import javax.annotation.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

// the host's end of the tunnel. Keeps one UDP socket to the relay (outbound, so no port forwarding is needed), says
// hello until the relay assigns an address, then keeps the session alive and forwards every joiner's datagrams to the
// game server through one PeerLink each. Reconnects on its own when the relay restarts or stops answering.
// Thread-safe: the receive loop runs on a virtual thread, timers on one scheduler thread, peer links on their own
// virtual threads. Callbacks to the listener come from those threads
public final class TunnelClient {

    public static final int DEFAULT_PORT = 5521;

    private static final System.Logger LOGGER = System.getLogger("e4hytale");
    private static final Duration TICK = Duration.ofSeconds(1);
    // hellos sent to one relay address before trying the next one (an AAAA record may be wrong or IPv6 broken)
    private static final int HELLOS_PER_ADDRESS = 3;
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);
    // a joiner without traffic for this long is gone. QUIC sends keepalives far more often
    private static final Duration PEER_IDLE_TIMEOUT = Duration.ofSeconds(60);
    // until the welcome says otherwise: what fits a 1280 byte IPv6 path through the tunnel
    private static final int FALLBACK_MAX_PAYLOAD = 1200;

    private final String relayHost;
    private final int relayPort;
    private final byte[] hostKey;
    private final String software;
    private final InetSocketAddress target;
    private final Listener listener;
    private final Map<Integer, PeerLink> links = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    @Nullable
    private DatagramChannel channel;
    @Nullable
    private ScheduledExecutorService scheduler;
    private List<InetSocketAddress> relayAddresses = List.of();
    private int addressIndex;
    private int hellosSent;
    private long nextHelloNanos;
    private int failures;
    private volatile State state = State.STOPPED;
    @Nullable
    private volatile InetSocketAddress relayAddress;
    private volatile long session;
    @Nullable
    private volatile String address;
    private volatile int maxPayload = FALLBACK_MAX_PAYLOAD;
    private volatile int keepaliveSeconds = 15;
    private volatile long lastHeardNanos;
    private volatile long lastPingNanos;
    private volatile long rttNanos = -1;

    public TunnelClient(String relayHost, int relayPort, byte[] hostKey, String software, InetSocketAddress target,
                        Listener listener) {
        if (hostKey.length != Frame.HOST_KEY_LENGTH) throw new IllegalArgumentException("Host key must be 32 bytes");

        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.hostKey = hostKey.clone();
        this.software = software;
        this.target = target;
        this.listener = listener;
    }

    public enum State {
        // saying hello, either for the first time or after losing the relay
        CONNECTING,
        ONLINE,
        STOPPED
    }

    // what the host gets told. Called from the tunnel's threads, so implementations must not block
    public interface Listener {

        // the relay assigned this address, joiners type it into Direct Connect
        void online(String address);

        // the tunnel went down. retrying is false when the relay refused the host for good (outdated, limit)
        void offline(String reason, boolean retrying);

        void joined(int peer, InetSocketAddress address);

        void left(int peer);

        // a message from whoever runs the relay
        void notice(String message);
    }

    public record PeerStatus(int peer, InetSocketAddress address, int localPort, long bytesIn, long bytesOut,
                             long dropped) {}

    public record Status(State state, @Nullable String address, @Nullable InetSocketAddress relay, long rttMillis,
                         List<PeerStatus> peers) {}

    public void start() throws IOException {
        synchronized (this.lock) {
            if (this.state != State.STOPPED) return;

            this.channel = DatagramChannel.open();
            this.channel.bind(null);
            this.state = State.CONNECTING;
            this.failures = 0;
            this.nextHelloNanos = System.nanoTime();
            DatagramChannel opened = this.channel;
            Thread.ofVirtual().name("e4hytale-tunnel").start(() -> this.receiveLoop(opened));
            this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon()
                    .name("e4hytale-timer").factory());
            this.scheduler.scheduleWithFixedDelay(this::tick, 0, TICK.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    // says goodbye so the relay frees the address at once, then closes everything. No listener calls afterwards
    public void stop() {
        synchronized (this.lock) {
            if (this.state == State.STOPPED) return;

            if (this.state == State.ONLINE) {
                this.sendControl(new Goodbye(this.session));
            }

            this.state = State.STOPPED;
            if (this.scheduler != null) {
                this.scheduler.shutdownNow();
                this.scheduler = null;
            }

            this.closeLinks();
            try {
                if (this.channel != null) this.channel.close();
            } catch (IOException e) {
                // nothing left to clean up if the socket is already broken
                LOGGER.log(System.Logger.Level.DEBUG, "Closing the tunnel socket failed: {0}", e.getMessage());
            }

            this.channel = null;
            this.address = null;
        }
    }

    public State state() {
        return this.state;
    }

    // the assigned address, null until online
    @Nullable
    public String address() {
        return this.address;
    }

    public InetSocketAddress target() {
        return this.target;
    }

    public Status status() {
        List<PeerStatus> peers = new ArrayList<>();
        for (PeerLink link : this.links.values()) {
            peers.add(new PeerStatus(link.peer(), link.address(), link.localPort(), link.bytesIn(), link.bytesOut(),
                    link.dropped()));
        }

        long rtt = this.rttNanos < 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(this.rttNanos);
        return new Status(this.state, this.address, this.relayAddress, rtt, peers);
    }

    // the joiner a local port belongs to, so the server's view (127.0.0.1:port) can be mapped to the real address
    @Nullable
    public InetSocketAddress joinerAt(int localPort) {
        for (PeerLink link : this.links.values()) {
            if (link.localPort() == localPort) return link.address();
        }

        return null;
    }

    int maxPayload() {
        return this.maxPayload;
    }

    // called by peer links for every server datagram, `out` is the link's own encode buffer
    void sendData(int peer, ByteBuffer payload, ByteBuffer out) {
        DatagramChannel channel = this.channel;
        InetSocketAddress relay = this.relayAddress;
        if (this.state != State.ONLINE || channel == null || relay == null) return;

        FrameCodec.encodeData(this.session, peer, payload, out);
        this.send(channel, out, relay);
    }

    private void tick() {
        try {
            long now = System.nanoTime();
            if (this.state == State.CONNECTING && now - this.nextHelloNanos >= 0) {
                this.sayHello(now);
            } else if (this.state == State.ONLINE) {
                long silence = now - this.lastHeardNanos;
                if (silence > TimeUnit.SECONDS.toNanos(this.keepaliveSeconds * 3L)) {
                    this.lose("The relay stopped answering", true);
                    return;
                }

                if (now - this.lastPingNanos > TimeUnit.SECONDS.toNanos(this.keepaliveSeconds)) {
                    this.lastPingNanos = now;
                    this.sendControl(new Ping(this.session, now));
                }
            }

            this.closeIdleLinks(now);
        } catch (RuntimeException e) {
            // a failed tick must not cancel the schedule, the next one tries again
            LOGGER.log(System.Logger.Level.WARNING, "Tunnel tick failed", e);
        }
    }

    private void sayHello(long now) {
        if (this.hellosSent >= HELLOS_PER_ADDRESS) {
            this.hellosSent = 0;
            this.addressIndex++;
            if (this.addressIndex >= this.relayAddresses.size()) {
                // every address had its chance: pause, then look the relay up again
                this.relayAddresses = List.of();
                this.fail("The relay " + this.relayHost + " does not answer, retrying", now);
                return;
            }

            this.relayAddress = this.relayAddresses.get(this.addressIndex);
        }

        if (this.relayAddresses.isEmpty() && !this.resolve()) {
            this.fail("Cannot find the relay " + this.relayHost + ", retrying", now);
            return;
        }

        this.hellosSent++;
        this.sendControl(new Hello(this.hostKey, this.software));
        this.nextHelloNanos = now + TICK.toNanos();
    }

    // looks the relay up again, so a moved relay or a fixed DNS record is picked up without a restart
    private boolean resolve() {
        try {
            List<InetSocketAddress> resolved = new ArrayList<>();
            for (InetAddress address : InetAddress.getAllByName(this.relayHost)) {
                resolved.add(new InetSocketAddress(address, this.relayPort));
            }

            this.relayAddresses = List.copyOf(resolved);
            this.addressIndex = 0;
            this.relayAddress = this.relayAddresses.getFirst();
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    // waits longer after every failed round. Only the first failure is reported, the host doesn't need one per retry
    private void fail(String reason, long now) {
        this.failures++;
        if (this.failures == 1) this.listener.offline(reason, true);

        long delay = Math.min(MAX_RETRY_DELAY.toNanos(), TICK.toNanos() << Math.min(this.failures, 5));
        // jitter, so hosts that lost the same relay don't all come back in the same second
        delay += ThreadLocalRandom.current().nextLong(TICK.toNanos());
        this.nextHelloNanos = now + delay;
    }

    private void receiveLoop(DatagramChannel channel) {
        ByteBuffer in = ByteBuffer.allocateDirect(65_535);
        while (channel.isOpen()) {
            try {
                in.clear();
                SocketAddress source = channel.receive(in);
                in.flip();
                // only the relay may talk to this socket, anything else is spoofed or stray
                if (source == null || !source.equals(this.relayAddress)) continue;

                Frame frame = FrameCodec.decode(in);
                if (frame != null) this.handle(frame);
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException e) {
                // windows reports an ICMP port unreachable from an earlier send here, the socket itself is fine
                LOGGER.log(System.Logger.Level.DEBUG, "Tunnel read failed: {0}", e.getMessage());
            } catch (RuntimeException e) {
                LOGGER.log(System.Logger.Level.WARNING, "Handling a tunnel frame failed", e);
            }
        }
    }

    private void handle(Frame frame) {
        if (this.state == State.STOPPED) return;

        if (frame instanceof Welcome welcome) {
            this.welcome(welcome);
            return;
        }

        if (frame instanceof Reject reject) {
            this.reject(reject);
            return;
        }

        if (this.state != State.ONLINE || sessionOf(frame) != this.session) return;

        this.lastHeardNanos = System.nanoTime();
        switch (frame) {
            case Data data -> {
                PeerLink link = this.link(data.peer(), null);
                if (link != null) link.deliver(data.payload());
            }
            case PeerOpen open -> {
                PeerLink link = this.link(open.peer(), open.address());
                if (link != null) this.listener.joined(open.peer(), open.address());
            }
            case PeerClose close -> {
                PeerLink link = this.links.remove(close.peer());
                if (link != null) {
                    link.close();
                    this.listener.left(close.peer());
                }
            }
            case Pong pong -> this.rttNanos = System.nanoTime() - pong.nonce();
            case Notice notice -> this.listener.notice(notice.message());
            default -> {}
        }
    }

    private void welcome(Welcome welcome) {
        synchronized (this.lock) {
            if (this.state == State.STOPPED) return;

            boolean changed = this.state != State.ONLINE || welcome.session() != this.session;
            if (changed) {
                // a new session means the relay forgot the old joiners
                this.closeLinks();
            }

            this.session = welcome.session();
            this.keepaliveSeconds = Math.max(1, welcome.keepaliveSeconds());
            this.maxPayload = welcome.maxPayload();
            this.lastHeardNanos = System.nanoTime();
            this.lastPingNanos = this.lastHeardNanos;
            this.failures = 0;
            this.hellosSent = 0;
            this.state = State.ONLINE;
            if (changed) {
                this.address = welcome.name();
                this.listener.online(welcome.name());
            }
        }
    }

    private void reject(Reject reject) {
        switch (reject.reason()) {
            case UNKNOWN_SESSION, SHUTDOWN -> this.lose(reject.message(), true);
            case OUTDATED, LIMIT, BAD_REQUEST -> {
                this.listener.offline(reject.message(), false);
                this.stop();
            }
        }
    }

    // back to saying hello, e.g. after the relay restarted. Joiners reconnect on their own once the address is back
    private void lose(String reason, boolean retrying) {
        synchronized (this.lock) {
            if (this.state != State.ONLINE) return;

            this.state = State.CONNECTING;
            this.closeLinks();
            this.hellosSent = 0;
            // counts as reported, so the retries that follow stay quiet
            this.failures = 1;
            this.nextHelloNanos = System.nanoTime();
        }

        this.listener.offline(reason, retrying);
    }

    // the link of a peer, opened on first sight. Data may arrive before the peer's PeerOpen or without it, if that
    // datagram got lost
    @Nullable
    private PeerLink link(int peer, @Nullable InetSocketAddress address) {
        PeerLink link = this.links.computeIfAbsent(peer, id -> {
            try {
                return new PeerLink(this, id, this.target,
                        address != null ? address : new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            } catch (IOException e) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not open a local socket for a joiner", e);
                return null;
            }
        });
        if (link != null && address != null) link.setAddress(address);

        return link;
    }

    private void closeIdleLinks(long now) {
        for (PeerLink link : this.links.values()) {
            if (now - link.lastActive() < PEER_IDLE_TIMEOUT.toNanos()) continue;

            if (this.links.remove(link.peer(), link)) {
                link.close();
                if (this.state == State.ONLINE) this.sendControl(new PeerClose(this.session, link.peer()));

                this.listener.left(link.peer());
            }
        }
    }

    private void closeLinks() {
        for (PeerLink link : this.links.values()) {
            link.close();
        }

        this.links.clear();
    }

    private void sendControl(Frame frame) {
        DatagramChannel channel = this.channel;
        InetSocketAddress relay = this.relayAddress;
        if (channel == null || relay == null) return;

        ByteBuffer out = ByteBuffer.allocate(Frame.HELLO_SIZE + 512);
        FrameCodec.encode(frame, out);
        this.send(channel, out, relay);
    }

    private void send(DatagramChannel channel, ByteBuffer frame, InetSocketAddress relay) {
        try {
            channel.send(frame, relay);
        } catch (ClosedChannelException e) {
            // stopping, the datagram is no longer wanted
        } catch (IOException e) {
            // no route right now (network switching, IPv6 down). UDP is lossy anyway, retries cover it
            LOGGER.log(System.Logger.Level.DEBUG, "Tunnel send failed: {0}", e.getMessage());
        }
    }

    private static long sessionOf(Frame frame) {
        return switch (frame) {
            case Data data -> data.session();
            case PeerOpen open -> open.session();
            case PeerClose close -> close.session();
            case Pong pong -> pong.session();
            case Notice notice -> notice.session();
            case Ping ping -> ping.session();
            case Goodbye goodbye -> goodbye.session();
            case Welcome welcome -> welcome.session();
            case Hello hello -> 0;
            case Reject reject -> 0;
        };
    }
}
