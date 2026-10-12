package com.yamikhal.e4hytale.relay.quic;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

// reads the server name (SNI) out of a TLS 1.3 ClientHello as QUIC carries it: handshake messages straight in the
// CRYPTO stream, no record layer (RFC 9001 section 4). The Hytale client puts the address the player typed there
public final class ClientHello {

    private static final int HANDSHAKE_CLIENT_HELLO = 1;
    private static final int EXTENSION_SERVER_NAME = 0;
    private static final int NAME_TYPE_HOST = 0;
    // a DNS name is at most 253 characters
    private static final int MAX_NAME_LENGTH = 253;

    private ClientHello() {}

    public sealed interface Result {}

    // more CRYPTO data is needed before the message is complete
    public record Incomplete() implements Result {}

    // the lower-case server name without a trailing dot
    public record Found(String serverName) implements Result {}

    // a complete ClientHello without a usable server name, or not a ClientHello at all
    public record Missing() implements Result {}

    public static Result serverName(byte[] stream) {
        ByteBuffer in = ByteBuffer.wrap(stream);
        try {
            if (in.remaining() < 4) return new Incomplete();

            if (in.get() != HANDSHAKE_CLIENT_HELLO) return new Missing();

            int length = readUint24(in);
            if (length > CryptoStream.MAX_LENGTH) return new Missing();

            if (in.remaining() < length) return new Incomplete();

            ByteBuffer hello = in.slice(in.position(), length);
            // legacy_version and random
            skip(hello, 2 + 32);
            skip(hello, Byte.toUnsignedInt(hello.get()));
            skip(hello, Short.toUnsignedInt(hello.getShort()));
            skip(hello, Byte.toUnsignedInt(hello.get()));
            ByteBuffer extensions = slice(hello, Short.toUnsignedInt(hello.getShort()));
            while (extensions.remaining() >= 4) {
                int type = Short.toUnsignedInt(extensions.getShort());
                ByteBuffer extension = slice(extensions, Short.toUnsignedInt(extensions.getShort()));
                if (type == EXTENSION_SERVER_NAME) return hostName(extension);
            }

            return new Missing();
        } catch (BufferUnderflowException | IllegalArgumentException | IndexOutOfBoundsException e) {
            return new Missing();
        }
    }

    private static Result hostName(ByteBuffer extension) {
        ByteBuffer names = slice(extension, Short.toUnsignedInt(extension.getShort()));
        while (names.hasRemaining()) {
            int type = Byte.toUnsignedInt(names.get());
            byte[] name = new byte[Short.toUnsignedInt(names.getShort())];
            names.get(name);
            if (type != NAME_TYPE_HOST || name.length == 0 || name.length > MAX_NAME_LENGTH + 1) continue;

            String host = new String(name, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
            return new Found(host.endsWith(".") ? host.substring(0, host.length() - 1) : host);
        }

        return new Missing();
    }

    private static int readUint24(ByteBuffer in) {
        return (Byte.toUnsignedInt(in.get()) << 16) | Short.toUnsignedInt(in.getShort());
    }

    private static void skip(ByteBuffer in, int count) {
        in.position(in.position() + count);
    }

    // the next `length` bytes as their own buffer, consumed from `in`
    private static ByteBuffer slice(ByteBuffer in, int length) {
        ByteBuffer slice = in.slice(in.position(), length);
        skip(in, length);
        return slice;
    }
}
