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
import com.yamikhal.e4hytale.tunnel.Frame.Reject;
import com.yamikhal.e4hytale.tunnel.Frame.Welcome;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class FrameCodecTest {

    @Test
    void roundTripsEveryFrameWithoutByteArrays() throws Exception {
        List<Frame> frames = List.of(
                new Welcome(9L, -7L, "jolly-otter.e4h.yamikhal.com", 15, 1200),
                new Reject(9L, Reason.LIMIT, "Too many worlds"),
                new PeerOpen(1L, 3, new InetSocketAddress(InetAddress.getByName("203.0.113.5"), 61_000)),
                new PeerOpen(1L, 4, new InetSocketAddress(InetAddress.getByName("2001:db8::1"), 5520)),
                new PeerClose(1L, 3),
                new Ping(9L, 123_456_789L),
                new Pong(9L, 123_456_789L),
                new Notice(2L, "Maintenance at 22:00, ünïcode"),
                new Goodbye(5L));
        for (Frame frame : frames) {
            assertEquals(frame, roundTrip(frame));
        }
    }

    @Test
    void padsHellosAndRegistrations() {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        FrameCodec.encode(new Hello(5L, "e4hytale/0.2.0"), buffer);
        assertEquals(Frame.PADDED_SIZE, buffer.remaining());
        assertEquals(new Hello(5L, "e4hytale/0.2.0"), FrameCodec.decode(buffer));

        byte[] cookie = filled(Frame.COOKIE_LENGTH, 1);
        byte[] key = filled(HostIdentity.PUBLIC_KEY_LENGTH, 2);
        byte[] signature = filled(HostIdentity.SIGNATURE_LENGTH, 3);
        FrameCodec.encode(new Register(6L, cookie, key, signature, "e4hytale/0.2.0"), buffer);
        assertEquals(Frame.PADDED_SIZE, buffer.remaining());
        Register register = assertInstanceOf(Register.class, FrameCodec.decode(buffer));
        assertEquals(6L, register.nonce());
        assertArrayEquals(cookie, register.cookie());
        assertArrayEquals(key, register.publicKey());
        assertArrayEquals(signature, register.signature());
        assertEquals("e4hytale/0.2.0", register.software());
    }

    @Test
    void carriesChallengeCookies() {
        byte[] cookie = filled(Frame.COOKIE_LENGTH, 9);
        Challenge challenge = assertInstanceOf(Challenge.class, roundTrip(new Challenge(77L, cookie)));
        assertEquals(77L, challenge.nonce());
        assertArrayEquals(cookie, challenge.cookie());
    }

    @Test
    void carriesDataPayloadsByteForByte() {
        ByteBuffer payload = ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5}, 1, 3);
        ByteBuffer buffer = ByteBuffer.allocate(64);
        FrameCodec.encodeData(77L, 12, payload, buffer);
        assertEquals(Frame.DATA_OVERHEAD + 3, buffer.remaining());

        Data data = assertInstanceOf(Data.class, FrameCodec.decode(buffer));
        assertEquals(77L, data.session());
        assertEquals(12, data.peer());
        assertEquals(ByteBuffer.wrap(new byte[] {2, 3, 4}), data.payload());
    }

    @Test
    void rejectsForeignTruncatedAndOversizedDatagrams() {
        assertNull(FrameCodec.decode(ByteBuffer.wrap(new byte[] {(byte) 0xC0, 0, 0, 0, 1})));
        assertNull(FrameCodec.decode(ByteBuffer.wrap(new byte[] {Frame.MAGIC, 99, 1})));
        assertNull(FrameCodec.decode(ByteBuffer.wrap(new byte[] {Frame.MAGIC, Frame.VERSION, 3, 0, 0})));
        assertEquals(99, FrameCodec.versionOf(ByteBuffer.wrap(new byte[] {Frame.MAGIC, 99, 1})));

        // a notice claiming a 60 000 byte message
        ByteBuffer huge = ByteBuffer.allocate(64).put(Frame.MAGIC).put(Frame.VERSION).put((byte) 10).putLong(1L)
                .putShort((short) 60_000);
        huge.flip();
        assertNull(FrameCodec.decode(huge));
    }

    private static Frame roundTrip(Frame frame) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        FrameCodec.encode(frame, buffer);
        return FrameCodec.decode(buffer);
    }

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
