package com.yamikhal.e4hytale.relay;

import com.yamikhal.e4hytale.relay.quic.InitialKeys;
import com.yamikhal.e4hytale.relay.quic.QuicPackets;
import com.yamikhal.e4hytale.tunnel.Frame;
import com.yamikhal.e4hytale.tunnel.Frame.Challenge;
import com.yamikhal.e4hytale.tunnel.Frame.Hello;
import com.yamikhal.e4hytale.tunnel.Frame.Register;
import com.yamikhal.e4hytale.tunnel.FrameCodec;
import com.yamikhal.e4hytale.tunnel.HostIdentity;
import com.yamikhal.e4hytale.tunnel.TunnelClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nullable;

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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the whole path on loopback: a joiner sends real QUIC Initials (from aioquic) to the relay, the relay routes them by
// server name through the tunnel to a TunnelClient, which hands them to a stand-in game server that echoes them. Also
// checks that the relay ignores registrations it can't trust and never answers with more than it got
class RelayEndToEndTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final HostIdentity IDENTITY = HostIdentity.fromSeed(HEX.parseHex(
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"));

    @TempDir
    Path data;

    private final CompletableFuture<String> online = new CompletableFuture<>();
    private RelayServer relay;
    private Thread relayThread;
    private DatagramChannel gameServer;
    private TunnelClient client;

    @BeforeEach
    void startEverything() throws Exception {
        // the aioquic Initials ask for jolly-otter, so that name is registered to this test's host up front
        String keyHash = HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(IDENTITY.publicKey()));
        Files.writeString(this.data.resolve("names.tsv"),
                "jolly-otter\t" + keyHash + "\t" + Instant.now().getEpochSecond() + "\n");

        RelayConfig config = RelayConfig.fromEnvironment(Map.of("E4H_DOMAIN", "e4h.yamikhal.com",
                "E4H_GAME_PORT", "0", "E4H_TUNNEL_PORT", "0", "E4H_HTTP_PORT", "0", "E4H_DATA", this.data.toString()));
        NameRegistry registry = new NameRegistry(this.data.resolve("names.tsv"), Duration.ofDays(90), 1000);
        registry.load(Instant.now());
        this.relay = new RelayServer(config, registry, new Metrics());
        this.relayThread = Thread.ofPlatform().start(this.relay::run);

        this.gameServer = DatagramChannel.open().bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Thread.ofVirtual().start(this::echo);

        this.client = new TunnelClient("127.0.0.1", this.relay.tunnelPort(), IDENTITY, "test",
                (InetSocketAddress) this.gameServer.getLocalAddress(), new OnlineListener(this.online));
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

        try (DatagramChannel joiner = socket()) {
            byte[] initial = datagrams("small").getFirst();
            joiner.send(ByteBuffer.wrap(initial), this.relayAddress(true));
            assertArrayEquals(initial, receive(joiner, 5_000));

            // later datagrams of the same joiner go the same way, whatever they contain
            byte[] next = "later datagram".getBytes(StandardCharsets.US_ASCII);
            joiner.send(ByteBuffer.wrap(next), this.relayAddress(true));
            assertArrayEquals(next, receive(joiner, 5_000));
        }

        assertEquals(1, this.client.status().peers().size());
    }

    @Test
    void joinerOfAnUnknownNameGetsAConnectionClose() throws Exception {
        this.online.get(5, TimeUnit.SECONDS);

        try (DatagramChannel joiner = socket()) {
            for (byte[] datagram : datagrams("big")) {
                joiner.send(ByteBuffer.wrap(datagram), this.relayAddress(true));
            }

            ByteBuffer refusal = ByteBuffer.wrap(receive(joiner, 5_000));
            assertTrue(QuicPackets.isLongHeader(refusal));
            assertEquals(InitialKeys.VERSION_1, QuicPackets.version(refusal));
        }

        assertTrue(this.client.status().peers().isEmpty());
    }

    @Test
    void releasedAddressRefusesJoinersAndAnotherKeyGetsAnotherName() throws Exception {
        this.online.get(5, TimeUnit.SECONDS);
        this.client.stop(true);

        CompletableFuture<String> renamed = new CompletableFuture<>();
        TunnelClient fresh = new TunnelClient("127.0.0.1", this.relay.tunnelPort(), HostIdentity.fromSeed(HostIdentity.newSeed()),
                "test", (InetSocketAddress) this.gameServer.getLocalAddress(), new OnlineListener(renamed));
        fresh.start();
        try {
            String address = renamed.get(5, TimeUnit.SECONDS);
            assertTrue(!address.equals("jolly-otter.e4h.yamikhal.com"), address);

            try (DatagramChannel joiner = socket()) {
                joiner.send(ByteBuffer.wrap(datagrams("small").getFirst()), this.relayAddress(true));
                ByteBuffer refusal = ByteBuffer.wrap(receive(joiner, 5_000));
                assertEquals(InitialKeys.VERSION_1, QuicPackets.version(refusal));
            }
        } finally {
            fresh.stop();
        }
    }

    @Test
    void registrationSignedWithAnotherKeyIsIgnored() throws Exception {
        this.online.get(5, TimeUnit.SECONDS);

        try (DatagramChannel attacker = socket()) {
            Challenge challenge = this.challenge(attacker, 7L);
            // claims the real host's key but can only sign with its own
            byte[] signature = HostIdentity.fromSeed(HostIdentity.newSeed()).sign(7L, challenge.cookie());
            this.sendTunnel(attacker, new Register(7L, challenge.cookie(), IDENTITY.publicKey(), signature, "attacker"));
            assertNull(receive(attacker, 1_000));
        }

        assertEquals("jolly-otter.e4h.yamikhal.com", this.client.address());
    }

    @Test
    void registrationWithoutTheChallengeCookieIsIgnored() throws Exception {
        try (DatagramChannel host = socket()) {
            HostIdentity identity = HostIdentity.fromSeed(HostIdentity.newSeed());
            byte[] cookie = new byte[Frame.COOKIE_LENGTH];
            this.sendTunnel(host, new Register(8L, cookie, identity.publicKey(), identity.sign(8L, cookie), "guess"));
            assertNull(receive(host, 1_000));
        }
    }

    @Test
    void neverAnswersWithMoreThanItReceived() throws Exception {
        try (DatagramChannel sender = socket()) {
            // a hello cut short: answering it would amplify a spoofed source
            ByteBuffer hello = ByteBuffer.allocate(Frame.PADDED_SIZE);
            FrameCodec.encode(new Hello(9L, "x"), hello);
            hello.limit(16);
            sender.send(hello, this.relayAddress(false));
            assertNull(receive(sender, 1_000));

            // an unknown session gets a reject no larger than the frame that asked
            ByteBuffer ping = ByteBuffer.allocate(64);
            FrameCodec.encode(new Frame.Ping(12345L, 1L), ping);
            int sent = ping.remaining();
            sender.send(ping, this.relayAddress(false));
            byte[] reply = receive(sender, 2_000);
            assertTrue(reply != null && reply.length <= sent);
        }
    }

    private Challenge challenge(DatagramChannel socket, long nonce) throws IOException {
        this.sendTunnel(socket, new Hello(nonce, "test"));
        byte[] reply = receive(socket, 2_000);
        assertTrue(reply != null);
        return assertInstanceOf(Challenge.class, FrameCodec.decode(ByteBuffer.wrap(reply)));
    }

    private void sendTunnel(DatagramChannel socket, Frame frame) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(2048);
        FrameCodec.encode(frame, buffer);
        socket.send(buffer, this.relayAddress(false));
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

    private InetSocketAddress relayAddress(boolean game) throws IOException {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), game ? this.relay.gamePort() : this.relay.tunnelPort());
    }

    private static DatagramChannel socket() throws IOException {
        return DatagramChannel.open().bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    }

    // null if nothing arrives in time
    @Nullable
    private static byte[] receive(DatagramChannel socket, int timeoutMillis) throws IOException {
        DatagramPacket packet = new DatagramPacket(new byte[65_535], 65_535);
        socket.socket().setSoTimeout(timeoutMillis);
        try {
            socket.socket().receive(packet);
        } catch (SocketTimeoutException e) {
            return null;
        }

        return Arrays.copyOf(packet.getData(), packet.getLength());
    }

    private static List<byte[]> datagrams(String set) throws IOException {
        List<byte[]> datagrams = new ArrayList<>();
        try (InputStream in = RelayEndToEndTest.class.getResourceAsStream("/aioquic-initials.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                String[] parts = line.trim().split(" ");
                if (parts.length == 2 && parts[0].equals(set)) {
                    datagrams.add(HEX.parseHex(parts[1]));
                }
            }
        }

        return datagrams;
    }

    private record OnlineListener(CompletableFuture<String> online) implements TunnelClient.Listener {

        @Override
        public void online(String address) {
            this.online.complete(address);
        }

        @Override
        public void offline(String reason, boolean retrying) {}

        @Override
        public void joined(int peer, InetSocketAddress address) {}

        @Override
        public void left(int peer) {}

        @Override
        public void notice(String message) {}
    }
}
