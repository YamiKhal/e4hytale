package com.yamikhal.e4hytale.tunnel;

import com.yamikhal.e4hytale.tunnel.Frame.Challenge;
import com.yamikhal.e4hytale.tunnel.Frame.Hello;
import com.yamikhal.e4hytale.tunnel.Frame.Reason;
import com.yamikhal.e4hytale.tunnel.Frame.Register;
import com.yamikhal.e4hytale.tunnel.Frame.Reject;
import com.yamikhal.e4hytale.tunnel.Frame.Welcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// the host's side of the handshake against a scripted relay: it only goes online for a welcome that answers its own
// attempt, and it signs the challenge with its identity
class TunnelClientTest {

    private final CompletableFuture<String> online = new CompletableFuture<>();
    private final CompletableFuture<String> refused = new CompletableFuture<>();
    private HostIdentity identity;
    private DatagramChannel relay;
    private TunnelClient client;

    @BeforeEach
    void start() throws IOException {
        this.identity = HostIdentity.fromSeed(HostIdentity.newSeed());
        this.relay = DatagramChannel.open().bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        this.relay.socket().setSoTimeout(3_000);
        InetSocketAddress relayAddress = (InetSocketAddress) this.relay.getLocalAddress();
        this.client = new TunnelClient("127.0.0.1", relayAddress.getPort(), this.identity, "test",
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 9), new Recorder());
        this.client.start();
    }

    @AfterEach
    void stop() throws IOException {
        this.client.stop();
        this.relay.close();
    }

    @Test
    void goesOnlineOnlyForAWelcomeToItsOwnSignedRegistration() throws Exception {
        Received<Hello> hello = this.receive(Hello.class);

        // a welcome for another attempt, and one without any registration, are both ignored
        this.send(new Welcome(hello.frame().nonce() + 1, 5L, "forged.e4h.example.com", 15, 1200), hello.source());
        Thread.sleep(300);
        assertEquals(TunnelClient.State.CONNECTING, this.client.state());

        byte[] cookie = new byte[Frame.COOKIE_LENGTH];
        cookie[0] = 1;
        this.send(new Challenge(hello.frame().nonce(), cookie), hello.source());
        Received<Register> register = this.receive(Register.class);
        assertTrue(HostIdentity.verify(register.frame().publicKey(), register.frame().nonce(), cookie,
                register.frame().signature()));

        this.send(new Welcome(register.frame().nonce(), 5L, "jolly-otter.e4h.example.com", 15, 1200), hello.source());
        assertEquals("jolly-otter.e4h.example.com", this.online.get(3, TimeUnit.SECONDS));
    }

    @Test
    void ignoresWelcomesWhoseNameIsNoHostName() throws Exception {
        Received<Hello> hello = this.receive(Hello.class);
        this.send(new Welcome(hello.frame().nonce(), 5L, "Click here: <evil>", 15, 1200), hello.source());
        Thread.sleep(300);
        assertEquals(TunnelClient.State.CONNECTING, this.client.state());
    }

    @Test
    void stopsOnlyForRejectsOfItsOwnAttempt() throws Exception {
        Received<Hello> hello = this.receive(Hello.class);
        this.send(new Reject(hello.frame().nonce() + 1, Reason.LIMIT, "forged"), hello.source());
        Thread.sleep(300);
        assertEquals(TunnelClient.State.CONNECTING, this.client.state());

        this.send(new Reject(hello.frame().nonce(), Reason.LIMIT, "Too many worlds"), hello.source());
        assertEquals("Too many worlds", this.refused.get(3, TimeUnit.SECONDS));
        assertEquals(TunnelClient.State.STOPPED, this.client.state());
    }

    // the next frame of this type the client sends, skipping repeats of others
    private <T extends Frame> Received<T> receive(Class<T> type) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(65_535);
        for (int i = 0; i < 20; i++) {
            buffer.clear();
            SocketAddress source;
            try {
                source = this.relay.socket().getChannel().receive(buffer);
            } catch (SocketTimeoutException e) {
                break;
            }

            buffer.flip();
            Frame frame = FrameCodec.decode(buffer);
            if (type.isInstance(frame)) {
                return new Received<>(type.cast(frame), (InetSocketAddress) source);
            }
        }

        throw new AssertionError("The client sent no " + type.getSimpleName());
    }

    private void send(Frame frame, InetSocketAddress target) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(2048);
        FrameCodec.encode(frame, buffer);
        this.relay.send(buffer, target);
    }

    private record Received<T extends Frame>(T frame, InetSocketAddress source) {}

    private final class Recorder implements TunnelClient.Listener {

        @Override
        public void online(String address) {
            TunnelClientTest.this.online.complete(address);
        }

        @Override
        public void offline(String reason, boolean retrying) {
            if (!retrying) {
                TunnelClientTest.this.refused.complete(reason);
            }
        }

        @Override
        public void joined(int peer, InetSocketAddress address) {}

        @Override
        public void left(int peer) {}

        @Override
        public void notice(String message) {}
    }
}
