package com.yamikhal.e4hytale.tunnel;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

// one datagram of the tunnel between a host and the relay (layout in FrameCodec). The tunnel is not encrypted: the
// game traffic inside is TLS 1.3 already, and everything else is either signed or tied to a random value only the
// two ends know. Registration takes three steps so the host's private key never travels and a spoofed source address
// gets nothing:
//   host  -> Hello(nonce)                                     the nonce is fresh for every connection attempt
//   relay -> Challenge(nonce, cookie)                         the cookie is bound to the host's address and the minute
//   host  -> Register(nonce, cookie, public key, signature)   signed with the host's Ed25519 key (see HostIdentity)
//   relay -> Welcome(nonce, session, name, ...)
// From then on frames carry the session id, which only the host and the relay know. Replies from the relay echo the
// nonce or the session id, so the host can tell them from forgeries
public sealed interface Frame {

    byte MAGIC = (byte) 0xE4;
    byte VERSION = 2;
    // hellos and registrations are padded to this size so a spoofed one never makes the relay send more than it got
    int PADDED_SIZE = 256;
    int COOKIE_LENGTH = 16;
    // magic + version + type + session + peer, the cost of tunnelling one game datagram
    int DATA_OVERHEAD = 3 + Long.BYTES + Integer.BYTES;

    // host -> relay, repeated until a challenge arrives
    record Hello(long nonce, String software) implements Frame {}

    // relay -> host
    record Challenge(long nonce, byte[] cookie) implements Frame {}

    // host -> relay, proves the host owns publicKey (32 byte Ed25519). The relay keys the host's name on that key
    record Register(long nonce, byte[] cookie, byte[] publicKey, byte[] signature, String software) implements Frame {}

    // relay -> host. Game datagrams larger than maxPayload must be dropped, never split
    record Welcome(long nonce, long session, String name, int keepaliveSeconds, int maxPayload) implements Frame {}

    // relay -> host, the attempt or the session was refused. token = the nonce or the session id it answers
    record Reject(long token, Reason reason, String message) implements Frame {}

    // relay -> host, a joiner showed up or moved. Purely informative: data for an unknown peer opens it as well
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

    // host -> relay, the host stops hosting and retires its name, before it registers with a new key. Signed (see
    // HostIdentity#signRelease): someone who saw the session id may end the session, but never take the name away
    record Release(long session, byte[] signature) implements Frame {}

    enum Reason {
        // the relay doesn't know the session, usually because it restarted. The host starts over
        UNKNOWN_SESSION,
        // too many hosts from this address, too many new names, or the relay is full
        LIMIT,
        BAD_REQUEST,
        SHUTDOWN
    }
}
