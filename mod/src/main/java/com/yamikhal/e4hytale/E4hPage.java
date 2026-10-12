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
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.yamikhal.e4hytale.E4HytaleConfig.StartMode;
import com.yamikhal.e4hytale.tunnel.TunnelClient;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// the e4hytale panel (/e4h): the address in a read-only field to select and copy, a Copy button that puts it into
// the clipboard (hosted worlds only, the server shares the PC with the game), a New address button (asks first),
// state, relay, players, start / stop and whether sharing starts on its own. Only opened for players allowed to
// control e4hytale (see E4hCommand). Refreshes itself every two seconds while open, built and updated on the player's
// world thread
final class E4hPage extends InteractiveCustomUIPage<E4hPage.PageEvent> {

    private static final long REFRESH_SECONDS = 2;

    private final E4Hytale plugin;
    private final World world;
    @Nullable
    private ScheduledFuture<?> refresher;
    // the copy hint, replaced by the result of the last copy
    private Message hint;
    // what the address field shows, so it is only written when it changes
    @Nullable
    private String shownAddress;
    // the New address button was pressed once and waits for the confirmation
    private boolean confirmingNewAddress;

    E4hPage(PlayerRef playerRef, World world, E4Hytale plugin) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageEvent.CODEC);
        this.plugin = plugin;
        this.world = world;
        this.hint = defaultHint();
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commands, @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        commands.append("Pages/E4Hytale.ui");
        commands.set("#CopyButton.Visible", Constants.SINGLEPLAYER);
        this.shownAddress = null;
        this.fill(commands);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CopyButton", action(Action.Copy));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#StartButton", action(Action.Start));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#StopButton", action(Action.Stop));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AutoOnButton", action(Action.AutoOn));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AutoOffButton", action(Action.AutoOff));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NewAddressButton", action(Action.NewAddress));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmNewAddressButton",
                action(Action.ConfirmNewAddress));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#KeepAddressButton", action(Action.KeepAddress));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", action(Action.Close));
        if (this.refresher == null) {
            this.refresher = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(this::scheduleRefresh,
                    REFRESH_SECONDS, REFRESH_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageEvent data) {
        // checked again on every press, the permission may be gone since the panel opened
        UUID player = this.playerRef.getUuid();
        boolean permitted = PermissionsModule.get().hasPermission(player, E4hCommand.permission(this.plugin));
        if (!this.plugin.mayControl(player, permitted)) {
            this.close();
            return;
        }

        switch (data.action) {
            case Copy -> this.copy();
            case Start -> this.plugin.startTunnel();
            case Stop -> this.plugin.stopTunnel(true);
            case AutoOn -> this.plugin.setStartMode(StartMode.ALWAYS);
            case AutoOff -> this.plugin.setStartMode(StartMode.ASK);
            case NewAddress -> this.confirmingNewAddress = true;
            case ConfirmNewAddress -> {
                this.confirmingNewAddress = false;
                this.hint = defaultHint();
                this.plugin.newAddress();
            }
            case KeepAddress -> this.confirmingNewAddress = false;
            case Close -> this.close();
        }

        if (data.action != Action.Close) {
            this.refresh();
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
        if (address == null) {
            return;
        }

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
        Universe universe = Universe.get();
        if (universe == null || universe.getPlayer(this.playerRef.getUuid()) == null) {
            // the player left without closing the page
            this.stopRefreshing();
            return;
        }

        try {
            this.world.execute(this::refresh);
        } catch (RuntimeException e) {
            // the world stopped (shutdown, player moved worlds): nothing left to refresh
            this.stopRefreshing();
        }
    }

    private void refresh() {
        UICommandBuilder commands = new UICommandBuilder();
        this.fill(commands);
        this.sendUpdate(commands, false);
    }

    private void fill(UICommandBuilder commands) {
        TunnelClient tunnel = this.plugin.tunnel();
        TunnelClient.Status status = tunnel == null ? null : tunnel.status();
        String address = status == null ? null : status.address();
        String shown = address != null ? address : "";
        if (!shown.equals(this.shownAddress)) {
            commands.set("#Address.Value", shown);
            this.shownAddress = shown;
        }

        commands.set("#State.TextSpans", StatusText.state(status));
        commands.set("#CopyHint.TextSpans", address != null ? this.hint : Message.translation("e4hytale.ui.noAddress"));
        commands.set("#Relay.TextSpans", StatusText.relay(this.plugin.relay(), status));
        commands.set("#PlayersCaption.TextSpans", StatusText.playersCaption(status));
        commands.set("#PlayerList.TextSpans", joinLines(StatusText.players(this.plugin, status)));
        commands.set("#StartButton.Visible", tunnel == null);
        commands.set("#StopButton.Visible", tunnel != null);
        boolean auto = this.plugin.startMode() == StartMode.ALWAYS;
        commands.set("#AutoOnButton.Visible", !auto);
        commands.set("#AutoOffButton.Visible", auto);
        commands.set("#NewAddressButton.Visible", !this.confirmingNewAddress);
        commands.set("#NewAddressConfirm.Visible", this.confirmingNewAddress);
    }

    private void stopRefreshing() {
        if (this.refresher != null) {
            this.refresher.cancel(false);
            this.refresher = null;
        }
    }

    private static Message joinLines(List<Message> lines) {
        Message joined = Message.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                joined.insert("\n");
            }

            joined.insert(lines.get(i));
        }

        return joined;
    }

    private static Message defaultHint() {
        return Message.translation(Constants.SINGLEPLAYER ? "e4hytale.ui.copyHint" : "e4hytale.ui.copyHintRemote");
    }

    private static EventData action(Action action) {
        return new EventData().append("Action", action.name());
    }

    enum Action {
        Copy,
        Start,
        Stop,
        AutoOn,
        AutoOff,
        NewAddress,
        ConfirmNewAddress,
        KeepAddress,
        Close
    }

    // what the panel sends back when a button is pressed
    static final class PageEvent {

        static final BuilderCodec<PageEvent> CODEC = BuilderCodec.builder(PageEvent.class, PageEvent::new)
                .append(new KeyedCodec<>("Action", new EnumCodec<>(Action.class)), (data, action) -> data.action = action,
                        data -> data.action)
                .add()
                .build();

        private Action action = Action.Close;
    }
}
