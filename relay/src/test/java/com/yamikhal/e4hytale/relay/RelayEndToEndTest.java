package com.yamikhal.e4hytale.relay;

import com.yamikhal.e4hytale.relay.quic.InitialKeys;
import com.yamikhal.e4hytale.relay.quic.QuicPackets;
import com.yamikhal.e4hytale.tunnel.TunnelClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the whole path on loopback: a joiner sends real QUIC Initials (from aioquic) to the relay, the relay routes them
// by server name through the tunnel to a TunnelClient, which hands them to a stand-in game server that echoes them.
// The echo has to come back to the joiner
class RelayEndToEndTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] HOST_KEY = new byte[32];

    @TempDir
    Path data;

    private RelayServer relay;
    private Thread relayThread;
    private DatagramChannel gameServer;
    private TunnelClient client;
    private final CompletableFuture<String> online = new CompletableFuture<>();

    @BeforeEach
    void startEverything() throws Exception {
        // the aioquic Initials ask for jolly-otter, so that name is registered to this test's host key up front
        HOST_KEY[0] = 42;
        String keyHash = HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(HOST_KEY));
        Files.writeString(this.data.resolve("names.tsv"),
                "jolly-otter\t" + keyHash + "\t" + Instant.now().getEpochSecond() + "\n");

        RelayConfig config = RelayConfig.fromEnvironment(Map.of("E4H_DOMAIN", "e4h.yamikhal.com",
                "E4H_GAME_PORT", "0", "E4H_TUNNEL_PORT", "0", "E4H_HTTP_PORT", "0", "E4H_DATA", this.data.toString()));
        NameRegistry registry = new NameRegistry(this.data.resolve("names.tsv"), Duration.ofDays(90));
        registry.load(Instant.now());
        this.relay = new RelayServer(config, registry, new Metrics());
        this.relayThread = Thread.ofPlatform().start(this.relay::run);

        this.gameServer = DatagramChannel.open().bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Thread.ofVirtual().start(this::echo);

        this.client = new TunnelClient("127.0.0.1", this.relay.tunnelPort(), HOST_KEY, "test",
                (InetSocketAddress) this.gameServer.getLocalAddress(), new TunnelClient.Listener() {
                    @Override
                    public void online(String address) {
                        RelayEndToEndTest.this.online.complete(address);
                    }

                    @Override
                    public void offline(String reason, boolean retrying) {}

                    @Override
                    public void joined(int peer, InetSocketAddress address) {}

                    @Override
                    public void left(int peer) {}

                    @Override
                    public void notice(String message) {}
                });
        this.client.start();
    }

    @AfterEach
    void stopEverything() throws Exception {
        this.client.stop();
        this.relay.stop();
        this.relayThread.join(5_000);
        this.gameServer.close();
    }

    @Test
    void hostKeepsItsRegisteredNameAndJoinerTrafficMakesTheRoundTrip() throws Exception {
        assertEquals("jolly-otter.e4h.yamikhal.com", this.online.get(5, TimeUnit.SECONDS));

        try (DatagramChannel joiner = joiner()) {
            byte[] initial = datagrams("small").getFirst();
            joiner.send(ByteBuffer.wrap(initial), relayGameAddress());
            assertArrayEquals(initial, receive(joiner));

            // later datagrams of the same joiner go the same way, whatever they contain
            byte[] next = "later datagram".getBytes(StandardCharsets.US_ASCII);
            joiner.send(ByteBuffer.wrap(next), relayGameAddress());
            assertArrayEquals(next, receive(joiner));
        }

        assertEquals(1, this.client.status().peers().size());
    }

    @Test
    void joinerOfAnUnknownNameGetsAConnectionClose() throws Exception {
        this.online.get(5, TimeUnit.SECONDS);

        try (DatagramChannel joiner = joiner()) {
            for (byte[] datagram : datagrams("big")) {
                joiner.send(ByteBuffer.wrap(datagram), relayGameAddress());
            }

            ByteBuffer refusal = ByteBuffer.wrap(receive(joiner));
            assertTrue(QuicPackets.isLongHeader(refusal));
            assertEquals(InitialKeys.VERSION_1, QuicPackets.version(refusal));
        }

        assertTrue(this.client.status().peers().isEmpty());
    }

    private void echo() {
        ByteBuffer buffer = ByteBuffer.allocate(65_535);
        while (this.gameServer.isOpen()) {
            try {
                buffer.clear();
                SocketAddress source = this.gameServer.receive(buffer);
                buffer.flip();
                this.gameServer.send(buffer, source);
            } catch (IOException e) {
                return;
            }
        }
    }

    private InetSocketAddress relayGameAddress() throws IOException {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), this.relay.gamePort());
    }

    private static DatagramChannel joiner() throws IOException {
        DatagramChannel joiner = DatagramChannel.open().bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        joiner.socket().setSoTimeout(5_000);
        return joiner;
    }

    private static byte[] receive(DatagramChannel joiner) throws IOException {
        DatagramPacket packet = new DatagramPacket(new byte[65_535], 65_535);
        try {
            joiner.socket().receive(packet);
        } catch (SocketTimeoutException e) {
            throw new AssertionError("Nothing came back within 5 seconds", e);
        }

        return Arrays.copyOf(packet.getData(), packet.getLength());
    }

    private static List<byte[]> datagrams(String set) throws IOException {
        List<byte[]> datagrams = new ArrayList<>();
        try (InputStream in = RelayEndToEndTest.class.getResourceAsStream("/aioquic-initials.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                String[] parts = line.trim().split(" ");
                if (parts.length == 2 && parts[0].equals(set)) datagrams.add(HEX.parseHex(parts[1]));
            }
        }

        return datagrams;
    }
}
