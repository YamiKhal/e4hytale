# e4hytale

Open your Hytale world to friends, anywhere, without port forwarding. Like [e4mc](https://e4mc.link), but for Hytale.

## Install

Put `e4hytale-<version>.jar` into `%APPDATA%/Hytale/UserData/Mods`. Only the host needs it, friends need nothing.

## Usage

1. Open your world to friends as usual.
2. If they can't join, type `/e4h` and press **Start sharing**.
3. Send them the address it gives you, like `jolly-otter.e4h.yamikhal.com`. It's already in your clipboard.
4. Friends join through **Direct Connect** with that address.

Your world keeps the same address every time. Want a different one? Press **New address** in the panel.

Hytale's own sharing works for many players, so e4hytale only suggests itself when you open a world. Turn on
**Auto start** in the panel to have it start on its own.

## Commands

- `/e4h` opens the panel: the address, Copy, New address, Start / Stop, Auto start, and who joined.
- `/e4h start`, `/e4h stop`
- `/e4h status`
- `/e4h copy`
- `/e4h newaddress --confirm`

Only the world's owner can use them. On a dedicated server they need the `yamikhal.e4hytale.command.e4h` permission.

## Good to know

- Anyone with the address can join, even if your world is set to friends only. Want to choose who gets in? Use
  `/op self`, then `/whitelist` or `/ban`.
- Setting the world back to Private stops sharing.
- Your address belongs to your world's `HostKey` in `Saves/<world>/mods/YamiKhal_e4hytale/config.json`. Never share
  it.
- Game traffic stays encrypted end to end. The relay passes it along and can't read it.

## Running your own relay

The relay is a small Java app in `relay/`, deployed with `docker-compose.yml` (for example through Coolify).

1. Point `*.e4h.<your domain>` and `e4h.<your domain>` to your server.
2. Allow **UDP 5520** and **UDP 5521** in.
3. Deploy, with `E4H_DOMAIN` set to `e4h.<your domain>`. Other settings are in `RelayConfig`.
4. Set `Relay` in the mod's config to your relay.

## Building

`gradlew build` builds and tests everything. `gradlew :mod:deployClient` puts the mod into the game.

## License

[MIT](LICENSE)
