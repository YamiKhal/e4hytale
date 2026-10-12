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

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class FrameCodecTest {

    @Test
    void roundTripsEveryRecordFrame() throws Exception {
        List<Frame> frames = List.of(
                new Welcome(-7L, "jolly-otter.e4h.yamikhal.com", 15, 1200),
                new Reject(Reason.LIMIT, "Too many worlds"),
                new PeerOpen(1L, 3, new InetSocketAddress(InetAddress.getByName("203.0.113.5"), 61_000)),
                new PeerOpen(1L, 4, new InetSocketAddress(InetAddress.getByName("2001:db8::1"), 5520)),
                new PeerClose(1L, 3),
                new Ping(9L, 123_456_789L),
                new Pong(9L, 123_456_789L),
                new Notice(2L, "Maintenance at 22:00 — ünïcode"),
                new Goodbye(5L));
        for (Frame frame : frames) {
            assertEquals(frame, roundTrip(frame));
        }
    }

    @Test
    void padsHellosAndKeepsTheirKey() {
        byte[] key = new byte[Frame.HOST_KEY_LENGTH];
        key[31] = 9;
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        FrameCodec.encode(new Hello(key, "e4hytale/0.1.0"), buffer);
        assertEquals(Frame.HELLO_SIZE, buffer.remaining());

        Hello hello = assertInstanceOf(Hello.class, FrameCodec.decode(buffer));
        assertArrayEquals(key, hello.hostKey());
        assertEquals("e4hytale/0.1.0", hello.software());
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
    void rejectsForeignAndTruncatedDatagrams() {
        assertNull(FrameCodec.decode(ByteBuffer.wrap(new byte[] {(byte) 0xC0, 0, 0, 0, 1})));
        assertNull(FrameCodec.decode(ByteBuffer.wrap(new byte[] {Frame.MAGIC, 99, 1})));
        assertNull(FrameCodec.decode(ByteBuffer.wrap(new byte[] {Frame.MAGIC, Frame.VERSION, 1, 0, 0})));
        assertEquals(99, FrameCodec.versionOf(ByteBuffer.wrap(new byte[] {Frame.MAGIC, 99, 1})));
    }

    private static Frame roundTrip(Frame frame) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        FrameCodec.encode(frame, buffer);
        return FrameCodec.decode(buffer);
    }
}
