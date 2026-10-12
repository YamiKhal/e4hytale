package com.yamikhal.e4hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Constants;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.yamikhal.e4hytale.tunnel.TunnelClient;

import javax.annotation.Nonnull;

// /e4h opens the e4hytale panel, /e4h start | stop | status | copy do the same from chat or the server console.
// Open to everyone at the command level because a hosted world's owner usually has no permissions in survival, the
// real check is E4Hytale#mayControl
final class E4hCommand extends AbstractPlayerCommand {

    private final E4Hytale plugin;

    E4hCommand(E4Hytale plugin) {
        super("e4h", "e4hytale.commands.e4h.desc");
        this.plugin = plugin;
        this.requireNoPermission();
        this.addSubCommand(new Start(plugin));
        this.addSubCommand(new Stop(plugin));
        this.addSubCommand(new Status(plugin));
        this.addSubCommand(new Copy(plugin));
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef, @Nonnull World world) {
        if (!mayControl(this.plugin, context)) return;

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) player.getPageManager().openCustomPage(ref, store, new E4hPage(playerRef, world, this.plugin));
    }

    // tells the sender off when they may not control the tunnel
    private static boolean mayControl(E4Hytale plugin, CommandContext context) {
        CommandSender sender = context.sender();
        if (plugin.mayControl(sender.getUuid(), sender.hasPermission(plugin.getBasePermission() + ".command.e4h"))) {
            return true;
        }

        context.sendMessage(Message.translation("e4hytale.notOwner"));
        return false;
    }

    private abstract static class Sub extends CommandBase {

        final E4Hytale plugin;

        Sub(E4Hytale plugin, String name) {
            super(name, "e4hytale.commands.e4h." + name + ".desc");
            this.plugin = plugin;
            this.requireNoPermission();
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            if (mayControl(this.plugin, context)) this.run(context);
        }

        abstract void run(CommandContext context);
    }

    private static final class Start extends Sub {

        Start(E4Hytale plugin) {
            super(plugin, "start");
        }

        @Override
        void run(CommandContext context) {
            this.plugin.startTunnel();
        }
    }

    private static final class Stop extends Sub {

        Stop(E4Hytale plugin) {
            super(plugin, "stop");
        }

        @Override
        void run(CommandContext context) {
            if (!this.plugin.stopTunnel(true)) context.sendMessage(Message.translation("e4hytale.notRunning"));
        }
    }

    private static final class Copy extends Sub {

        Copy(E4Hytale plugin) {
            super(plugin, "copy");
        }

        @Override
        void run(CommandContext context) {
            TunnelClient tunnel = this.plugin.tunnel();
            String address = tunnel == null ? null : tunnel.address();
            if (address == null) {
                context.sendMessage(Message.translation("e4hytale.notRunning"));
                return;
            }

            if (!Constants.SINGLEPLAYER) {
                // the clipboard of a dedicated server's machine helps nobody
                context.sendMessage(Message.translation("e4hytale.address").param("address", Message.raw(address).bold(true)));
                return;
            }

            Thread.ofVirtual().start(() -> context.sendMessage(Clipboard.copy(address)
                    ? Message.translation("e4hytale.copied").param("address", Message.raw(address).bold(true))
                    : Message.translation("e4hytale.copyFailed").param("address", Message.raw(address).bold(true))));
        }
    }

    private static final class Status extends Sub {

        Status(E4Hytale plugin) {
            super(plugin, "status");
        }

        @Override
        void run(CommandContext context) {
            TunnelClient tunnel = this.plugin.tunnel();
            TunnelClient.Status status = tunnel == null ? null : tunnel.status();
            Message state = Message.translation("e4hytale.status").param("state", StatusText.state(status));
            if (status != null && status.address() != null) {
                state.insert(" ").insert(Message.raw(status.address()).bold(true));
            }

            context.sendMessage(state);
            if (status == null) return;

            context.sendMessage(StatusText.relay(this.plugin.relay(), status));
            context.sendMessage(StatusText.playersCaption(status));
            for (Message line : StatusText.players(this.plugin, status)) {
                context.sendMessage(line);
            }
        }
    }
}
