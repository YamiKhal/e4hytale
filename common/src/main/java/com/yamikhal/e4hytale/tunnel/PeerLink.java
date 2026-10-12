package com.yamikhal.e4hytale.tunnel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.atomic.AtomicLong;

// the local end of one joiner: a UDP socket connected to the game server, so the server sees the joiner as
// 127.0.0.1:<this socket's port>. Datagrams from the relay go out through it, the server's answers come back on a
// virtual thread and go into the tunnel. Created and closed by TunnelClient
final class PeerLink {

    private static final System.Logger LOGGER = System.getLogger("e4hytale");

    private final TunnelClient client;
    private final int peer;
    private final DatagramChannel channel;
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile long lastActive = System.nanoTime();
    private volatile InetSocketAddress address;

    PeerLink(TunnelClient client, int peer, InetSocketAddress target, InetSocketAddress address) throws IOException {
        this.client = client;
        this.peer = peer;
        this.address = address;
        this.channel = DatagramChannel.open(target.getAddress().getAddress().length == 4
                ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6);
        this.channel.connect(target);
        Thread.ofVirtual().name("e4hytale-peer-" + peer).start(this::receiveLoop);
    }

    int peer() {
        return this.peer;
    }

    // where the joiner connects from, as the relay sees it (unresolved 0.0.0.0 until the relay said)
    InetSocketAddress address() {
        return this.address;
    }

    void setAddress(InetSocketAddress address) {
        this.address = address;
    }

    long bytesIn() {
        return this.bytesIn.get();
    }

    long bytesOut() {
        return this.bytesOut.get();
    }

    // server datagrams too large for the tunnel, see receiveLoop
    long dropped() {
        return this.dropped.get();
    }

    long lastActive() {
        return this.lastActive;
    }

    int localPort() {
        try {
            return this.channel.getLocalAddress() instanceof InetSocketAddress local ? local.getPort() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    // a datagram from the joiner, handed to the server
    void deliver(ByteBuffer payload) {
        int size = payload.remaining();
        try {
            this.channel.write(payload);
            this.bytesIn.addAndGet(size);
            this.lastActive = System.nanoTime();
        } catch (IOException e) {
            // the server isn't listening (world closing). QUIC retransmits, and the link times out if it stays so
            LOGGER.log(System.Logger.Level.DEBUG, "Could not deliver a datagram to the server: {0}", e.getMessage());
        }
    }

    void close() {
        try {
            this.channel.close();
        } catch (IOException e) {
            // closing a datagram socket only fails if it is already gone, which is the goal anyway
            LOGGER.log(System.Logger.Level.DEBUG, "Closing a peer link failed: {0}", e.getMessage());
        }
    }

    // the server picks its own packet sizes and believes this loopback socket takes 5000 bytes (QuicheListener grants
    // loopback peers a large ceiling). The real path to the joiner takes about 1200, so larger datagrams, which are
    // only path MTU probes while the connection stays at the 1200 floor, are dropped: the probe fails and the server
    // keeps sizes the tunnel carries. Splitting them instead would make every large packet depend on fragmentation
    private void receiveLoop() {
        ByteBuffer in = ByteBuffer.allocateDirect(65_535);
        ByteBuffer out = ByteBuffer.allocateDirect(65_535 + Frame.DATA_OVERHEAD);
        while (this.channel.isOpen()) {
            try {
                in.clear();
                this.channel.read(in);
                in.flip();
                this.lastActive = System.nanoTime();
                if (in.remaining() > this.client.maxPayload()) {
                    this.dropped.incrementAndGet();
                    continue;
                }

                this.bytesOut.addAndGet(in.remaining());
                this.client.sendData(this.peer, in, out);
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException e) {
                // windows reports an ICMP port unreachable from an earlier send here, the socket itself is fine
                LOGGER.log(System.Logger.Level.DEBUG, "Peer link read failed: {0}", e.getMessage());
            }
        }
    }
}
