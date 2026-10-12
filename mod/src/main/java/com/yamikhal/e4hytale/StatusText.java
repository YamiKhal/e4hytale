package com.yamikhal.e4hytale;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.yamikhal.e4hytale.tunnel.TunnelClient;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

// the texts that describe the tunnel, shared by /e4h status in chat and the e4hytale panel
final class StatusText {

    private StatusText() {}

    // "Online", "Connecting to the relay..." or "Not sharing"
    static Message state(@Nullable TunnelClient.Status status) {
        if (status == null) return Message.translation("e4hytale.ui.state.stopped");

        return status.state() == TunnelClient.State.ONLINE
                ? Message.translation("e4hytale.ui.state.online").color("#55FF55")
                : Message.translation("e4hytale.ui.state.connecting").color("#E8A93B");
    }

    // relay name, ping and time online
    static Message relay(String relay, @Nullable TunnelClient.Status status) {
        if (status == null || status.state() != TunnelClient.State.ONLINE) {
            return Message.translation("e4hytale.ui.relayOffline").param("relay", relay);
        }

        return Message.translation("e4hytale.ui.relay")
                .param("relay", relay)
                .param("rtt", status.rttMillis() < 0 ? "?" : String.valueOf(status.rttMillis()))
                .param("time", Format.duration(status.onlineSeconds()));
    }

    static Message playersCaption(@Nullable TunnelClient.Status status) {
        return Message.translation("e4hytale.ui.playersCaption").param("count", status == null ? 0 : status.peers().size());
    }

    // one line per joiner, named once the joiner is logged in. Addresses stay out (players share their screens)
    static List<Message> players(E4Hytale plugin, @Nullable TunnelClient.Status status) {
        List<Message> lines = new ArrayList<>();
        if (status == null || status.peers().isEmpty()) {
            lines.add(Message.translation("e4hytale.ui.nobody"));
            return lines;
        }

        for (TunnelClient.PeerStatus peer : status.peers()) {
            PlayerRef player = plugin.playerAt(peer.localPort());
            Message name = player != null
                    ? Message.raw(player.getUsername()).bold(true)
                    : Message.translation("e4hytale.playerJoining");
            Message dropped = peer.dropped() > 0
                    ? Message.translation("e4hytale.dropped").param("count", peer.dropped())
                    : Message.empty();
            lines.add(Message.translation("e4hytale.player")
                    .param("name", name)
                    .param("time", Format.duration(peer.connectedSeconds()))
                    .param("out", Format.bytes(peer.bytesOut()))
                    .param("outRate", Format.bytes(peer.rateOut()))
                    .param("in", Format.bytes(peer.bytesIn()))
                    .param("inRate", Format.bytes(peer.rateIn()))
                    .param("dropped", dropped));
        }

        return lines;
    }
}
