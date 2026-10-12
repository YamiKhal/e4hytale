package com.yamikhal.e4hytale.tunnel;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

// one datagram of the tunnel between a host and the relay. Every frame starts with MAGIC, VERSION and a type byte,
// numbers are big-endian, strings are a u16 length plus UTF-8. Frames after the handshake carry the session id the
// relay handed out, which is all that ties a datagram to a host (the game traffic inside is TLS 1.3 already)
public sealed interface Frame {

    byte MAGIC = (byte) 0xE4;
    byte VERSION = 1;
    // a hello is padded to this size so a spoofed one never makes the relay send more than it received
    int HELLO_SIZE = 256;
    int HOST_KEY_LENGTH = 32;
    // magic + version + type + session + peer, the cost of tunnelling one game datagram
    int DATA_OVERHEAD = 3 + Long.BYTES + Integer.BYTES;

    // host -> relay, repeated until a welcome arrives. The host key is a random secret the host keeps, so it gets the
    // same address every time
    record Hello(byte[] hostKey, String software) implements Frame {}

    // relay -> host. Game datagrams larger than maxPayload must be dropped, never split
    record Welcome(long session, String name, int keepaliveSeconds, int maxPayload) implements Frame {}

    // relay -> host, the session (or the hello) was refused
    record Reject(Reason reason, String message) implements Frame {}

    // relay -> host, a joiner showed up. Purely informative: data for an unknown peer opens it as well
    record PeerOpen(long session, int peer, InetSocketAddress address) implements Frame {}

    // both ways, one game datagram of one joiner. The payload is a view into the received buffer
    record Data(long session, int peer, ByteBuffer payload) implements Frame {}

    // both ways, the joiner left or timed out
    record PeerClose(long session, int peer) implements Frame {}

    // host -> relay keepalive, which also holds the host's NAT mapping open, answered by a pong
    record Ping(long session, long nonce) implements Frame {}

    record Pong(long session, long nonce) implements Frame {}

    // relay -> host, a message for the host to show (maintenance and the like)
    record Notice(long session, String message) implements Frame {}

    // host -> relay, the host stops hosting
    record Goodbye(long session) implements Frame {}

    enum Reason {
        // the host speaks a tunnel version the relay doesn't
        OUTDATED,
        // the relay doesn't know the session, usually because it restarted. The host says hello again
        UNKNOWN_SESSION,
        // too many hosts from this address, or the relay is full
        LIMIT,
        BAD_REQUEST,
        SHUTDOWN
    }
}
