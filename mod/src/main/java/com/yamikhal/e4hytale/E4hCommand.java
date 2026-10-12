package com.yamikhal.e4hytale;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.yamikhal.e4hytale.tunnel.TunnelClient;

import javax.annotation.Nonnull;

// /e4h start | stop | status. Open to everyone at the command level because a hosted world's owner usually has no
// permissions in survival, the real check is E4Hytale#mayControl
final class E4hCommand extends AbstractCommandCollection {

    E4hCommand(E4Hytale plugin) {
        super("e4h", "e4hytale.commands.e4h.desc");
        this.requireNoPermission();
        this.addSubCommand(new Start(plugin));
        this.addSubCommand(new Stop(plugin));
        this.addSubCommand(new Status(plugin));
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
            CommandSender sender = context.sender();
            if (!this.plugin.mayControl(sender.getUuid(), sender.hasPermission(this.plugin.getBasePermission() + ".command.e4h"))) {
                context.sendMessage(Message.translation("e4hytale.notOwner"));
                return;
            }

            this.run(context);
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

    private static final class Status extends Sub {

        Status(E4Hytale plugin) {
            super(plugin, "status");
        }

        @Override
        void run(CommandContext context) {
            TunnelClient tunnel = this.plugin.tunnel();
            if (tunnel == null) {
                context.sendMessage(Message.translation("e4hytale.status.stopped"));
                return;
            }

            TunnelClient.Status status = tunnel.status();
            if (status.state() != TunnelClient.State.ONLINE || status.address() == null) {
                context.sendMessage(Message.translation("e4hytale.status.connecting").param("relay", this.plugin.relay()));
                return;
            }

            context.sendMessage(Message.translation("e4hytale.status.online")
                    .param("address", Message.raw(status.address()).bold(true))
                    .param("rtt", status.rttMillis())
                    .param("count", status.peers().size()));
            for (TunnelClient.PeerStatus peer : status.peers()) {
                context.sendMessage(Message.translation("e4hytale.status.peer")
                        .param("address", peer.address().getAddress().getHostAddress())
                        .param("port", peer.localPort())
                        .param("in", peer.bytesIn() / 1024)
                        .param("out", peer.bytesOut() / 1024));
            }
        }
    }
}
