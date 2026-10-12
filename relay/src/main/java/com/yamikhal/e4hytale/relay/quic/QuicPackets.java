package com.yamikhal.e4hytale.relay.quic;

import javax.annotation.Nullable;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

// just enough QUIC v1 (RFC 9000, 9001) for a relay that routes by server name: open the client's Initial packets to
// get at their CRYPTO frames, read connection ids off headers, and write one Initial with a CONNECTION_CLOSE to turn
// a joiner away. Nothing here keeps state
public final class QuicPackets {

    private static final int TAG_LENGTH = 16;
    private static final int SAMPLE_LENGTH = 16;
    private static final int MAX_CID_LENGTH = 20;
    // QUIC transport error code for a server that refuses the connection
    private static final int CONNECTION_REFUSED = 0x02;
    private static final int TYPE_INITIAL = 0;

    private QuicPackets() {}

    // the decrypted payload of one client Initial packet, and the connection ids it carried
    public record Initial(byte[] dcid, byte[] scid, long packetNumber, byte[] payload) {}

    public static boolean isLongHeader(ByteBuffer datagram) {
        return datagram.remaining() > 0 && (datagram.get(datagram.position()) & 0x80) != 0;
    }

    // the version of a long header packet, 0 for a version negotiation packet, -1 for a short header or garbage
    public static long version(ByteBuffer datagram) {
        if (!isLongHeader(datagram) || datagram.remaining() < 5) return -1;

        return Integer.toUnsignedLong(datagram.getInt(datagram.position() + 1));
    }

    // opens every Initial packet coalesced into a datagram. Other packet types are skipped, packets that fail to
    // decrypt or parse end the datagram (what follows can't be trusted). keysFor gives the client keys of a dcid
    public static List<Initial> openClientInitials(ByteBuffer datagram, Function<byte[], InitialKeys> keysFor) {
        List<Initial> opened = new ArrayList<>();
        byte[] bytes = new byte[datagram.remaining()];
        datagram.duplicate().get(bytes);
        int offset = 0;
        while (offset < bytes.length && (bytes[offset] & 0x80) != 0) {
            try {
                ByteBuffer packet = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
                int first = Byte.toUnsignedInt(packet.get());
                if (packet.getInt() != InitialKeys.VERSION_1) break;

                byte[] dcid = readCid(packet);
                byte[] scid = readCid(packet);
                int type = (first >>> 4) & 0x03;
                if (type == TYPE_INITIAL) {
                    long tokenLength = readVarint(packet);
                    packet.position(packet.position() + Math.toIntExact(tokenLength));
                }

                int length = Math.toIntExact(readVarint(packet));
                int pnOffset = packet.position();
                int end = pnOffset + length;
                if (end > bytes.length || length < 4 + SAMPLE_LENGTH) break;

                if (type == TYPE_INITIAL) {
                    Initial initial = open(bytes, offset, pnOffset, end, dcid, scid, keysFor.apply(dcid));
                    if (initial == null) break;

                    opened.add(initial);
                }

                offset = end;
            } catch (BufferUnderflowException | IllegalArgumentException | ArithmeticException
                     | IndexOutOfBoundsException e) {
                // a truncated or forged packet, nothing after it is usable
                break;
            }
        }

        return opened;
    }

    // feeds the CRYPTO frames of an Initial payload to the assembler. False if the payload holds a frame that may not
    // appear in a client Initial, or is malformed
    public static boolean readCryptoFrames(byte[] payload, CryptoStream stream) {
        ByteBuffer in = ByteBuffer.wrap(payload);
        try {
            while (in.hasRemaining()) {
                long type = readVarint(in);
                switch ((int) type) {
                    case 0x00, 0x01 -> {}
                    case 0x02, 0x03 -> {
                        readVarint(in);
                        readVarint(in);
                        long ranges = readVarint(in);
                        readVarint(in);
                        for (long i = 0; i < ranges; i++) {
                            readVarint(in);
                            readVarint(in);
                        }

                        if (type == 0x03) {
                            readVarint(in);
                            readVarint(in);
                            readVarint(in);
                        }
                    }
                    case 0x06 -> {
                        long offset = readVarint(in);
                        byte[] data = new byte[Math.toIntExact(readVarint(in))];
                        in.get(data);
                        if (!stream.add(offset, data)) return false;
                    }
                    case 0x1c -> {
                        readVarint(in);
                        readVarint(in);
                        in.position(in.position() + Math.toIntExact(readVarint(in)));
                    }
                    default -> {
                        return false;
                    }
                }
            }

            return true;
        } catch (BufferUnderflowException | IllegalArgumentException | ArithmeticException e) {
            return false;
        }
    }

    // the source connection id of a long header packet (a server packet, to learn the ids the joiner will use), or
    // null for anything else
    @Nullable
    public static byte[] longHeaderScid(ByteBuffer datagram) {
        if (!isLongHeader(datagram)) return null;

        try {
            ByteBuffer packet = datagram.duplicate();
            packet.position(packet.position() + 5);
            readCid(packet);
            byte[] scid = readCid(packet);
            return scid.length > 0 ? scid : null;
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            return null;
        }
    }

    // the destination connection id of a short header packet, if it is at least `length` bytes long
    @Nullable
    public static byte[] shortHeaderDcid(ByteBuffer datagram, int length) {
        if (isLongHeader(datagram) || datagram.remaining() < 1 + length) return null;

        byte[] dcid = new byte[length];
        datagram.get(datagram.position() + 1, dcid);
        return dcid;
    }

    // a server Initial that closes the connection with CONNECTION_REFUSED and a reason phrase. clientDcid is the
    // destination id of the client's first Initial (it keys the server's Initial keys), clientScid the client's own id
    public static ByteBuffer refusal(byte[] clientDcid, byte[] clientScid, byte[] serverScid, String reason) {
        byte[] phrase = reason.getBytes(StandardCharsets.UTF_8);
        ByteBuffer frames = ByteBuffer.allocate(16 + phrase.length);
        frames.put((byte) 0x1c);
        writeVarint(frames, CONNECTION_REFUSED);
        writeVarint(frames, 0);
        writeVarint(frames, phrase.length);
        frames.put(phrase);
        // the header protection sample needs 16 bytes of ciphertext after the first 4 packet number bytes
        while (frames.position() < 4) {
            frames.put((byte) 0);
        }

        byte[] payload = Arrays.copyOf(frames.array(), frames.position());

        int packetNumberLength = 1;
        ByteBuffer header = ByteBuffer.allocate(64);
        header.put((byte) (0xC0 | (TYPE_INITIAL << 4) | (packetNumberLength - 1)));
        header.putInt(InitialKeys.VERSION_1);
        header.put((byte) clientScid.length).put(clientScid);
        header.put((byte) serverScid.length).put(serverScid);
        writeVarint(header, 0);
        // always the 2 byte varint form, which keeps the header length fixed
        header.putShort((short) (0x4000 | (packetNumberLength + payload.length + TAG_LENGTH)));
        int pnOffset = header.position();
        header.put((byte) 0);
        byte[] aad = Arrays.copyOf(header.array(), header.position());

        InitialKeys keys = InitialKeys.server(clientDcid);
        byte[] sealed = crypt(Cipher.ENCRYPT_MODE, keys, 0, aad, payload);
        byte[] packet = new byte[aad.length + sealed.length];
        System.arraycopy(aad, 0, packet, 0, aad.length);
        System.arraycopy(sealed, 0, packet, aad.length, sealed.length);

        byte[] mask = keys.mask(Arrays.copyOfRange(packet, pnOffset + 4, pnOffset + 4 + SAMPLE_LENGTH));
        packet[0] ^= (byte) (mask[0] & 0x0f);
        packet[pnOffset] ^= mask[1];
        return ByteBuffer.wrap(packet);
    }

    public static long readVarint(ByteBuffer in) {
        int first = Byte.toUnsignedInt(in.get());
        int length = 1 << (first >>> 6);
        long value = first & 0x3f;
        for (int i = 1; i < length; i++) {
            value = (value << 8) | Byte.toUnsignedInt(in.get());
        }

        return value;
    }

    public static void writeVarint(ByteBuffer out, long value) {
        if (value < 0x40) {
            out.put((byte) value);
        } else if (value < 0x4000) {
            out.putShort((short) (0x4000 | value));
        } else if (value < 0x4000_0000) {
            out.putInt((int) (0x8000_0000L | value));
        } else {
            out.putLong(0xC000_0000_0000_0000L | value);
        }
    }

    @Nullable
    private static Initial open(byte[] bytes, int start, int pnOffset, int end, byte[] dcid, byte[] scid,
                                InitialKeys keys) {
        byte[] mask = keys.mask(Arrays.copyOfRange(bytes, pnOffset + 4, pnOffset + 4 + SAMPLE_LENGTH));
        // works on a copy: a failed attempt must leave the datagram as received, it is forwarded verbatim later
        byte[] header = Arrays.copyOfRange(bytes, start, pnOffset + 4);
        header[0] ^= (byte) (mask[0] & 0x0f);
        int packetNumberLength = (header[0] & 0x03) + 1;
        int pnIndex = pnOffset - start;
        long packetNumber = 0;
        for (int i = 0; i < packetNumberLength; i++) {
            header[pnIndex + i] ^= mask[1 + i];
            packetNumber = (packetNumber << 8) | Byte.toUnsignedInt(header[pnIndex + i]);
        }

        byte[] aad = Arrays.copyOf(header, pnIndex + packetNumberLength);
        byte[] sealed = Arrays.copyOfRange(bytes, pnOffset + packetNumberLength, end);
        byte[] payload = crypt(Cipher.DECRYPT_MODE, keys, packetNumber, aad, sealed);
        return payload == null ? null : new Initial(dcid, scid, packetNumber, payload);
    }

    // AES-128-GCM seal or open, null if opening fails the tag check
    @Nullable
    private static byte[] crypt(int mode, InitialKeys keys, long packetNumber, byte[] aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(keys.key(), "AES"),
                    new GCMParameterSpec(TAG_LENGTH * 8, keys.nonce(packetNumber)));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (AEADBadTagException e) {
            return null;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    private static byte[] readCid(ByteBuffer packet) {
        int length = Byte.toUnsignedInt(packet.get());
        if (length > MAX_CID_LENGTH) throw new IllegalArgumentException("Connection id too long");

        byte[] cid = new byte[length];
        packet.get(cid);
        return cid;
    }
}
