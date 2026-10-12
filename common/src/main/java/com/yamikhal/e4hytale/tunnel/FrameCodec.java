package com.yamikhal.e4hytale.tunnel;

import com.yamikhal.e4hytale.tunnel.Frame.Data;
import com.yamikhal.e4hytale.tunnel.Frame.Goodbye;
import com.yamikhal.e4hytale.tunnel.Frame.Hello;
import com.yamikhal.e4hytale.tunnel.Frame.Notice;
import com.yamikhal.e4hytale.tunnel.Frame.PeerClose;
import com.yamikhal.e4hytale.tunnel.Frame.PeerOpen;
import com.yamikhal.e4hytale.tunnel.Frame.Ping;
import com.yamikhal.e4hytale.tunnel.Frame.Pong;
import com.yamikhal.e4hytale.tunnel.Frame.Reason;
import com.yamikhal.e4hytale.tunnel.Frame.Reject;
import com.yamikhal.e4hytale.tunnel.Frame.Welcome;

import javax.annotation.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

// reads and writes tunnel frames (see Frame for the layout)
public final class FrameCodec {

    private static final byte DATA = 4;

    private FrameCodec() {}

    // clears the buffer and encodes into it, flipped and ready to send afterwards
    public static void encode(Frame frame, ByteBuffer out) {
        out.clear();
        out.put(Frame.MAGIC).put(Frame.VERSION).put(typeOf(frame));
        switch (frame) {
            case Hello hello -> {
                out.put(hello.hostKey());
                putString(out, hello.software());
                while (out.position() < Frame.HELLO_SIZE) {
                    out.put((byte) 0);
                }
            }
            case Welcome welcome -> {
                out.putLong(welcome.session());
                putString(out, welcome.name());
                out.putShort((short) welcome.keepaliveSeconds());
                out.putShort((short) welcome.maxPayload());
            }
            case Reject reject -> {
                out.put((byte) reject.reason().ordinal());
                putString(out, reject.message());
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
                putString(out, notice.message());
            }
            case Goodbye goodbye -> out.putLong(goodbye.session());
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
            if (in.remaining() < 3 || in.get() != Frame.MAGIC || in.get() != Frame.VERSION) return null;

            return switch (in.get()) {
                case 0 -> {
                    byte[] key = new byte[Frame.HOST_KEY_LENGTH];
                    in.get(key);
                    yield new Hello(key, getString(in));
                }
                case 1 -> new Welcome(in.getLong(), getString(in), Short.toUnsignedInt(in.getShort()),
                        Short.toUnsignedInt(in.getShort()));
                case 2 -> {
                    int ordinal = Byte.toUnsignedInt(in.get());
                    Reason[] reasons = Reason.values();
                    yield new Reject(ordinal < reasons.length ? reasons[ordinal] : Reason.BAD_REQUEST, getString(in));
                }
                case 3 -> {
                    long session = in.getLong();
                    int peer = in.getInt();
                    byte[] address = new byte[Byte.toUnsignedInt(in.get())];
                    in.get(address);
                    yield new PeerOpen(session, peer, new InetSocketAddress(InetAddress.getByAddress(address),
                            Short.toUnsignedInt(in.getShort())));
                }
                case DATA -> new Data(in.getLong(), in.getInt(), in.slice());
                case 5 -> new PeerClose(in.getLong(), in.getInt());
                case 6 -> new Ping(in.getLong(), in.getLong());
                case 7 -> new Pong(in.getLong(), in.getLong());
                case 8 -> new Notice(in.getLong(), getString(in));
                case 9 -> new Goodbye(in.getLong());
                default -> null;
            };
        } catch (BufferUnderflowException | IllegalArgumentException | UnknownHostException e) {
            // a truncated or forged datagram, which the caller ignores like any other garbage
            return null;
        }
    }

    // the version byte of a datagram that starts with Frame.MAGIC, or -1. Lets the relay tell outdated hosts apart from noise
    public static int versionOf(ByteBuffer in) {
        if (in.remaining() < 2 || in.get(in.position()) != Frame.MAGIC) return -1;

        return Byte.toUnsignedInt(in.get(in.position() + 1));
    }

    private static byte typeOf(Frame frame) {
        return switch (frame) {
            case Hello hello -> 0;
            case Welcome welcome -> 1;
            case Reject reject -> 2;
            case PeerOpen open -> 3;
            case Data data -> DATA;
            case PeerClose close -> 5;
            case Ping ping -> 6;
            case Pong pong -> 7;
            case Notice notice -> 8;
            case Goodbye goodbye -> 9;
        };
    }

    private static void putString(ByteBuffer out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.putShort((short) bytes.length).put(bytes);
    }

    private static String getString(ByteBuffer in) {
        byte[] bytes = new byte[Short.toUnsignedInt(in.getShort())];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
