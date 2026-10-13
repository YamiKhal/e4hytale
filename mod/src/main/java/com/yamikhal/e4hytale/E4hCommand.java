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
        this.addSubCommand(new NewAddress(plugin));
    }

    @Override
    protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref,
                           PlayerRef playerRef, World world) {
        if (!mayControl(this.plugin, context)) {
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store, new E4hPage(playerRef, world, this.plugin));
        }
    }

    // what lets a player control e4hytale on a dedicated server
    static String permission(E4Hytale plugin) {
        return plugin.getBasePermission() + ".command.e4h";
    }

    // tells the sender off when they may not control the tunnel
    private static boolean mayControl(E4Hytale plugin, CommandContext context) {
        CommandSender sender = context.sender();
        if (plugin.mayControl(sender.getUuid(), sender.hasPermission(permission(plugin)))) {
            return true;
        }

        context.sendMessage(Message.translation("e4hytale.notOwner"));
        return false;
    }

    private abstract static class Sub extends CommandBase {

        final E4Hytale plugin;

        Sub(E4Hytale plugin, String name) {
            this(plugin, name, false);
        }

        // requiresConfirmation: the command only runs when repeated with --confirm (Hytale's own mechanism)
        Sub(E4Hytale plugin, String name, boolean requiresConfirmation) {
            super(name, "e4hytale.commands.e4h." + name + ".desc", requiresConfirmation);
            this.plugin = plugin;
            this.requireNoPermission();
        }

        @Override
        protected void executeSync(CommandContext context) {
            if (mayControl(this.plugin, context)) {
                this.run(context);
            }
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
            if (!this.plugin.stopTunnel(true)) {
                context.sendMessage(Message.translation("e4hytale.notRunning"));
            }
        }
    }

    private static final class NewAddress extends Sub {

        NewAddress(E4Hytale plugin) {
            super(plugin, "newaddress", true);
        }

        @Override
        void run(CommandContext context) {
            this.plugin.newAddress();
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
            if (status == null) {
                return;
            }

            context.sendMessage(StatusText.relay(this.plugin.relay(), status));
            context.sendMessage(StatusText.playersCaption(status));
            for (Message line : StatusText.players(this.plugin, status)) {
                context.sendMessage(line);
            }
        }
    }
}
