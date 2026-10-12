package com.yamikhal.e4hytale;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Constants;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.yamikhal.e4hytale.tunnel.TunnelClient;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// the e4hytale panel (/e4h): the address in a text field to select and copy, a Copy button that puts it into the
// clipboard (hosted worlds only, the server shares the PC with the game), state, relay, players and start / stop.
// Refreshes itself every two seconds while open. Built and updated on the player's world thread
final class E4hPage extends InteractiveCustomUIPage<E4hPage.PageEvent> {

    private static final long REFRESH_SECONDS = 2;

    private final E4Hytale plugin;
    private final World world;
    @Nullable
    private ScheduledFuture<?> refresher;
    // the copy hint, replaced by the result of the last copy
    private Message hint;
    @Nullable
    private String lastAddress;

    E4hPage(PlayerRef playerRef, World world, E4Hytale plugin) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageEvent.CODEC);
        this.plugin = plugin;
        this.world = world;
        this.hint = Message.translation(Constants.SINGLEPLAYER ? "e4hytale.ui.copyHint" : "e4hytale.ui.copyHintRemote");
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commands, @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        commands.append("Pages/E4Hytale.ui");
        commands.set("#CopyButton.Visible", Constants.SINGLEPLAYER);
        this.fill(commands, true);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CopyButton", action(Action.Copy));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#StartButton", action(Action.Start));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#StopButton", action(Action.Stop));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", action(Action.Close));
        if (this.refresher == null) {
            this.refresher = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(this::scheduleRefresh,
                    REFRESH_SECONDS, REFRESH_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageEvent data) {
        // the page only ever opens for players allowed to control the tunnel (see E4hCommand)
        switch (data.action) {
            case Copy -> this.copy();
            case Start -> {
                this.plugin.startTunnel();
                this.refresh();
            }
            case Stop -> {
                this.plugin.stopTunnel(true);
                this.refresh();
            }
            case Close -> this.close();
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        this.stopRefreshing();
    }

    // clipboard tools can take a moment, so the copy runs off the world thread and reports back to it
    private void copy() {
        TunnelClient tunnel = this.plugin.tunnel();
        String address = tunnel == null ? null : tunnel.address();
        if (address == null) return;

        Thread.ofVirtual().start(() -> {
            boolean copied = Clipboard.copy(address);
            this.world.execute(() -> {
                this.hint = copied
                        ? Message.translation("e4hytale.ui.copied").color("#55FF55")
                        : Message.translation("e4hytale.ui.copyFailed").color("#E8A93B");
                this.refresh();
            });
        });
    }

    private void scheduleRefresh() {
        try {
            this.world.execute(this::refresh);
        } catch (RuntimeException e) {
            // the world stopped (shutdown, player moved worlds): nothing left to refresh
            this.stopRefreshing();
        }
    }

    private void refresh() {
        UICommandBuilder commands = new UICommandBuilder();
        this.fill(commands, false);
        this.sendUpdate(commands, false);
    }

    // the address field is only written when it changes, so a selection the player is making isn't wiped every refresh
    private void fill(UICommandBuilder commands, boolean first) {
        TunnelClient tunnel = this.plugin.tunnel();
        TunnelClient.Status status = tunnel == null ? null : tunnel.status();
        String address = status == null ? null : status.address();
        String shown = address != null ? address : "";
        if (first || !shown.equals(this.lastAddress)) {
            commands.set("#Address.Value", shown);
            this.lastAddress = shown;
        }

        commands.set("#State.TextSpans", StatusText.state(status));
        commands.set("#CopyHint.TextSpans", address != null ? this.hint : Message.translation("e4hytale.ui.noAddress"));
        commands.set("#Relay.TextSpans", StatusText.relay(this.plugin.relay(), status));
        commands.set("#PlayersCaption.TextSpans", StatusText.playersCaption(status));
        List<Message> players = StatusText.players(this.plugin, status);
        Message list = Message.empty();
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) list.insert("\n");

            list.insert(players.get(i));
        }

        commands.set("#PlayerList.TextSpans", list);
        commands.set("#StartButton.Visible", tunnel == null);
        commands.set("#StopButton.Visible", tunnel != null);
    }

    private void stopRefreshing() {
        if (this.refresher != null) {
            this.refresher.cancel(false);
            this.refresher = null;
        }
    }

    private static EventData action(Action action) {
        return new EventData().append("Action", action.name());
    }

    enum Action {
        Copy,
        Start,
        Stop,
        Close
    }

    // what the panel sends back when a button is pressed
    static final class PageEvent {

        static final BuilderCodec<PageEvent> CODEC = BuilderCodec.builder(PageEvent.class, PageEvent::new)
                .append(new KeyedCodec<>("Action", new EnumCodec<>(Action.class)),
                        (data, action) -> data.action = action, data -> data.action)
                .add()
                .build();

        private Action action = Action.Close;
    }
}
