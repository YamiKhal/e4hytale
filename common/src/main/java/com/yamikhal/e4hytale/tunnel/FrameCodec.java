package com.yamikhal.e4hytale.tunnel;

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
import com.yamikhal.e4hytale.tunnel.Frame.Release;
import com.yamikhal.e4hytale.tunnel.Frame.Reject;
import com.yamikhal.e4hytale.tunnel.Frame.Welcome;

import javax.annotation.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

// reads and writes tunnel frames. Every frame starts with Frame.MAGIC, Frame.VERSION and a type byte. Numbers are
// big-endian, strings a u16 length plus UTF-8, byte arrays have the fixed lengths of their field
public final class FrameCodec {

    private static final byte HELLO = 0;
    private static final byte CHALLENGE = 1;
    private static final byte REGISTER = 2;
    private static final byte WELCOME = 3;
    private static final byte REJECT = 4;
    private static final byte PEER_OPEN = 5;
    private static final byte DATA = 6;
    private static final byte PEER_CLOSE = 7;
    private static final byte PING = 8;
    private static final byte PONG = 9;
    private static final byte NOTICE = 10;
    private static final byte GOODBYE = 11;
    private static final byte RELEASE = 12;
    // longest string a frame may carry, so every frame fits a datagram
    private static final int MAX_STRING_BYTES = 1024;

    private FrameCodec() {}

    // clears the buffer and encodes into it, flipped and ready to send afterwards
    public static void encode(Frame frame, ByteBuffer out) {
        out.clear();
        out.put(Frame.MAGIC).put(Frame.VERSION).put(typeOf(frame));
        switch (frame) {
            case Hello hello -> {
                out.putLong(hello.nonce());
                writeString(out, hello.software());
                pad(out);
            }
            case Challenge challenge -> out.putLong(challenge.nonce()).put(challenge.cookie());
            case Register register -> {
                out.putLong(register.nonce()).put(register.cookie()).put(register.publicKey()).put(register.signature());
                writeString(out, register.software());
                pad(out);
            }
            case Welcome welcome -> {
                out.putLong(welcome.nonce()).putLong(welcome.session());
                writeString(out, welcome.name());
                out.putShort((short) welcome.keepaliveSeconds()).putShort((short) welcome.maxPayload());
            }
            case Reject reject -> {
                out.putLong(reject.token()).put((byte) reject.reason().ordinal());
                writeString(out, reject.message());
            }
            case PeerOpen open -> {
                out.putLong(open.session()).putInt(open.peer());
                byte[] address = open.address().getAddress().getAddress();
                out.put((byte) address.length).put(address).putShort((short) open.address().getPort());
            }
            case Data data -> out.putLong(data.session()).putInt(data.peer()).put(data.payload().duplicate());
            case PeerClose close -> out.putLong(close.session()).putInt(close.peer());
            case Ping ping -> out.putLong(ping.session()).putLong(ping.nonce());
            case Pong pong -> out.putLong(pong.session()).putLong(pong.nonce());
            case Notice notice -> {
                out.putLong(notice.session());
                writeString(out, notice.message());
            }
            case Goodbye goodbye -> out.putLong(goodbye.session());
            case Release release -> out.putLong(release.session()).put(release.signature());
        }

        out.flip();
    }

    // writes a data frame without allocating a record, for the hot path. The payload goes from its position to its limit
    public static void encodeData(long session, int peer, ByteBuffer payload, ByteBuffer out) {
        out.clear();
        out.put(Frame.MAGIC).put(Frame.VERSION).put(DATA).putLong(session).putInt(peer).put(payload);
        out.flip();
    }

    // null if the datagram is not a frame of this version or is malformed
    @Nullable
    public static Frame decode(ByteBuffer in) {
        try {
            if (in.remaining() < 3 || in.get() != Frame.MAGIC || in.get() != Frame.VERSION) {
                return null;
            }

            return switch (in.get()) {
                case HELLO -> new Hello(in.getLong(), readString(in));
                case CHALLENGE -> new Challenge(in.getLong(), bytes(in, Frame.COOKIE_LENGTH));
                case REGISTER -> new Register(in.getLong(), bytes(in, Frame.COOKIE_LENGTH),
                        bytes(in, HostIdentity.PUBLIC_KEY_LENGTH), bytes(in, HostIdentity.SIGNATURE_LENGTH), readString(in));
                case WELCOME -> new Welcome(in.getLong(), in.getLong(), readString(in), Short.toUnsignedInt(in.getShort()),
                        Short.toUnsignedInt(in.getShort()));
                case REJECT -> {
                    long token = in.getLong();
                    int ordinal = Byte.toUnsignedInt(in.get());
                    Reason[] reasons = Reason.values();
                    yield new Reject(token, ordinal < reasons.length ? reasons[ordinal] : Reason.BAD_REQUEST, readString(in));
                }
                case PEER_OPEN -> {
                    long session = in.getLong();
                    int peer = in.getInt();
                    byte[] address = bytes(in, Byte.toUnsignedInt(in.get()));
                    yield new PeerOpen(session, peer, new InetSocketAddress(InetAddress.getByAddress(address),
                            Short.toUnsignedInt(in.getShort())));
                }
                case DATA -> new Data(in.getLong(), in.getInt(), in.slice());
                case PEER_CLOSE -> new PeerClose(in.getLong(), in.getInt());
                case PING -> new Ping(in.getLong(), in.getLong());
                case PONG -> new Pong(in.getLong(), in.getLong());
                case NOTICE -> new Notice(in.getLong(), readString(in));
                case GOODBYE -> new Goodbye(in.getLong());
                case RELEASE -> new Release(in.getLong(), bytes(in, HostIdentity.SIGNATURE_LENGTH));
                default -> null;
            };
        } catch (BufferUnderflowException | IllegalArgumentException | UnknownHostException e) {
            // a truncated or forged datagram, which the caller ignores like any other garbage
            return null;
        }
    }

    // the version byte of a datagram that starts with Frame.MAGIC, -1 for anything else. Tells a relay or host of
    // another version apart from noise
    public static int versionOf(ByteBuffer in) {
        if (in.remaining() < 2 || in.get(in.position()) != Frame.MAGIC) {
            return -1;
        }

        return Byte.toUnsignedInt(in.get(in.position() + 1));
    }

    private static byte typeOf(Frame frame) {
        return switch (frame) {
            case Hello hello -> HELLO;
            case Challenge challenge -> CHALLENGE;
            case Register register -> REGISTER;
            case Welcome welcome -> WELCOME;
            case Reject reject -> REJECT;
            case PeerOpen open -> PEER_OPEN;
            case Data data -> DATA;
            case PeerClose close -> PEER_CLOSE;
            case Ping ping -> PING;
            case Pong pong -> PONG;
            case Notice notice -> NOTICE;
            case Goodbye goodbye -> GOODBYE;
            case Release release -> RELEASE;
        };
    }

    private static void pad(ByteBuffer out) {
        while (out.position() < Frame.PADDED_SIZE) {
            out.put((byte) 0);
        }
    }

    private static void writeString(ByteBuffer out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(bytes.length, MAX_STRING_BYTES);
        out.putShort((short) length).put(bytes, 0, length);
    }

    private static String readString(ByteBuffer in) {
        int length = Short.toUnsignedInt(in.getShort());
        if (length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("String too long");
        }

        return new String(bytes(in, length), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(ByteBuffer in, int length) {
        byte[] bytes = new byte[length];
        in.get(bytes);
        return bytes;
    }
}
