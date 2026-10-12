package com.yamikhal.e4hytale.relay.quic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuicPacketsTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void derivesTheInitialKeysOfRfc9001AppendixA() {
        byte[] dcid = HEX.parseHex("8394c8f03e515708");

        InitialKeys client = InitialKeys.client(dcid);
        assertEquals("1f369613dd76d5467730efcbe3b1a22d", HEX.formatHex(client.key()));
        assertEquals("fa044b2f42a3fd3b46fb255c", HEX.formatHex(client.iv()));
        assertEquals("9f50449e04a0e810283a1e9933adedd2", HEX.formatHex(client.hp()));

        InitialKeys server = InitialKeys.server(dcid);
        assertEquals("cf3a5331653c364c88f0f379b6067e37", HEX.formatHex(server.key()));
        assertEquals("0ac1493ca1905853b0bba03e", HEX.formatHex(server.iv()));
        assertEquals("c206b8d9b9f0f37644430b490eeaa314", HEX.formatHex(server.hp()));
    }

    @Test
    void readsTheServerNameOfARealClientInitial() throws IOException {
        assertEquals(new ClientHello.Found("jolly-otter.e4h.yamikhal.com"), serverName(datagrams("small")));
    }

    @Test
    void readsAServerNameSpreadOverSeveralDatagrams() throws IOException {
        List<ByteBuffer> datagrams = datagrams("big");
        assertTrue(datagrams.size() > 1);
        assertEquals(new ClientHello.Found("big-hello.e4h.yamikhal.com"), serverName(datagrams));

        Collections.reverse(datagrams);
        assertEquals(new ClientHello.Found("big-hello.e4h.yamikhal.com"), serverName(datagrams));
    }

    @Test
    void waitsForTheRestOfASplitClientHello() throws IOException {
        List<ByteBuffer> datagrams = datagrams("big");
        assertInstanceOf(ClientHello.Incomplete.class, serverName(datagrams.subList(0, 1)));
    }

    @Test
    void leavesTheDatagramUntouchedWhileOpeningIt() throws IOException {
        ByteBuffer datagram = datagrams("small").getFirst();
        byte[] before = bytes(datagram);
        QuicPackets.openClientInitials(datagram, InitialKeys::client);
        assertArrayEquals(before, bytes(datagram));
    }

    @Test
    void ignoresDatagramsThatAreNotQuic() {
        ByteBuffer garbage = ByteBuffer.wrap("GET / HTTP/1.1\r\n".repeat(80).getBytes(StandardCharsets.US_ASCII));
        assertTrue(QuicPackets.openClientInitials(garbage, InitialKeys::client).isEmpty());

        ByteBuffer truncated = ByteBuffer.wrap(new byte[] {(byte) 0xC0, 0, 0, 0, 1, 8, 1, 2});
        assertTrue(QuicPackets.openClientInitials(truncated, InitialKeys::client).isEmpty());
    }

    @Test
    void writesARefusalThatOpensWithTheServerKeys() {
        byte[] clientDcid = HEX.parseHex("8394c8f03e515708");
        byte[] clientScid = HEX.parseHex("0102030405060708");
        ByteBuffer refusal = QuicPackets.refusal(clientDcid, clientScid, HEX.parseHex("aabbccdd"), "Nobody is here");

        // the client reads it with the server keys of its own first dcid, while the packet is addressed to its scid
        List<QuicPackets.Initial> opened = QuicPackets.openClientInitials(refusal, cid -> InitialKeys.server(clientDcid));
        assertEquals(1, opened.size());
        assertArrayEquals(clientScid, opened.getFirst().dcid());

        ByteBuffer frame = ByteBuffer.wrap(opened.getFirst().payload());
        assertEquals(0x1c, QuicPackets.readVarint(frame));
        assertEquals(0x02, QuicPackets.readVarint(frame));
        assertEquals(0, QuicPackets.readVarint(frame));
        byte[] reason = new byte[(int) QuicPackets.readVarint(frame)];
        frame.get(reason);
        assertEquals("Nobody is here", new String(reason, StandardCharsets.UTF_8));
    }

    @Test
    void roundTripsVarintsOfEveryLength() {
        for (long value : new long[] {0, 63, 64, 16_383, 16_384, 1_073_741_823, 1_073_741_824, 4_611_686_018_427_387_903L}) {
            ByteBuffer buffer = ByteBuffer.allocate(8);
            QuicPackets.writeVarint(buffer, value);
            buffer.flip();
            assertEquals(value, QuicPackets.readVarint(buffer));
        }
    }

    private static ClientHello.Result serverName(List<ByteBuffer> datagrams) {
        CryptoStream stream = new CryptoStream();
        for (ByteBuffer datagram : datagrams) {
            for (QuicPackets.Initial initial : QuicPackets.openClientInitials(datagram, InitialKeys::client)) {
                assertTrue(QuicPackets.readCryptoFrames(initial.payload(), stream));
            }
        }

        return ClientHello.serverName(stream.prefix());
    }

    // client Initial datagrams written by aioquic (see the resource's header), so the parser is checked against an
    // independent QUIC stack and not only against itself
    private static List<ByteBuffer> datagrams(String set) throws IOException {
        List<ByteBuffer> datagrams = new ArrayList<>();
        try (InputStream in = QuicPacketsTest.class.getResourceAsStream("/aioquic-initials.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                String[] parts = line.trim().split(" ");
                if (parts.length == 2 && parts[0].equals(set)) datagrams.add(ByteBuffer.wrap(HEX.parseHex(parts[1])));
            }
        }

        return datagrams;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }
}
