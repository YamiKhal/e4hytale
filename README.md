# e4hytale

Host a Hytale world for your friends without port forwarding. Like [e4mc](https://e4mc.link), for Hytale.

The host installs the mod. Opening the world to other players prints an address like
`jolly-otter.e4h.yamikhal.com`. Friends type it into **Direct Connect** and need nothing else.

## How it works

```text
friend's Hytale ──QUIC/UDP 5520──▶ relay ──tunnel, UDP 5521 (opened from the host's side)──▶ mod ──▶ 127.0.0.1:<world port>
```

- **Mod** (`mod/`): a Hytale plugin that runs in the hosted world's server process, or on a dedicated server.
  - It opens a UDP tunnel to the relay. Because the tunnel is outbound, the router needs no setup.
  - It passes each joiner's datagrams to the local server, one loopback socket per joiner.
  - It drops server datagrams that are too big for the internet path. See `PeerLink` for why.
- **Relay** (`relay/`): a standalone Java 25 app.
  1. It reads the server name from the joiner's QUIC Initial packet. The Hytale client sends the address the
     player typed as TLS SNI.
  2. It finds the host with that name and forwards the datagrams as they are.
  - TLS stays end to end, so Hytale's authentication works unchanged and the relay can't read game traffic.
  - Unknown names get a QUIC CONNECTION_CLOSE with a readable reason.
  - Names are tied to the host's key, so they persist across restarts.
- **common** (`common/`): the tunnel wire format (`Frame`, `FrameCodec`) and the host-side `TunnelClient`.

Background research: [../ONLINE.md](../ONLINE.md).

## Playing

1. Put `e4hytale-<version>.jar` into `%APPDATA%/Hytale/UserData/Mods` (or run `gradlew :mod:deployClient`).
2. Open your world to friends, as usual in the world's online settings. Chat shows the address.
3. Friends join through Direct Connect with that address. No port is needed.

Commands (world owner, or permission `yamikhal.e4hytale.command.e4h` on a dedicated server):

- `/e4h start`
- `/e4h stop`
- `/e4h status`: shows the address, the relay ping and who is connected.

`mods/YamiKhal_e4hytale/config.json` keys:

- `Relay`, `RelayPort`: which relay to use.
- `AutoStart`: on opening the world, or on boot for a dedicated server.
- `HostKey`: the secret that keeps your address. Never share it.

Setting the world back to Private stops sharing. Anyone with the address can join, just like a LAN world opened to
friends. Use the world password if that matters.

## Running the relay on Coolify (Hetzner)

1. DNS: `*.e4h.<your domain>` A (and AAAA, if correct) → the server.
2. Firewall (Hetzner Cloud Firewall and ufw, if used): allow **UDP 5520** and **UDP 5521** in.
   - The joiner port must be 5520 so addresses need no port.
   - Nothing else on the server may use UDP 5520 (for example a Hytale server).
3. Coolify → New Resource → this repository.
   - Build Pack **Docker Compose**.
   - Base Directory = the `e4hytale` folder.
   - Compose file `docker-compose.yml`.
4. Environment variables:
   - `E4H_DOMAIN` (default `e4h.yamikhal.com`).
   - Optional: `E4H_ADMIN_TOKEN` (enables `POST /broadcast`).
   - Optional: `E4H_MAX_HOSTS`, `E4H_MAX_PEERS_PER_HOST`.
   - More are listed in `RelayConfig`.
5. Deploy. The log should say `Relay for *.e4h... on UDP 5520 (joiners) and 5521 (hosts)`.
6. Check health from the server:

   ```sh
   docker exec <container> wget -qO- localhost:8080/metrics
   ```

   Or give the service a domain in Coolify for port 8080.

The `e4h-data` volume holds `names.tsv` (host names). Losing it gives every host a new address.

Broadcast a message to all hosts, for example before maintenance:

```sh
curl -X POST -H "Authorization: Bearer $TOKEN" --data "Relay restarts in 5 minutes" http://<relay>:8080/broadcast
```

## Development

| Task | Command |
| --- | --- |
| Build and test everything | `gradlew build` |
| Relay locally | `gradlew :relay:run` (env `E4H_GAME_PORT`, `E4H_TUNNEL_PORT`, `E4H_HTTP_PORT`, `E4H_DATA`) |
| Mod into the game | `gradlew :mod:deployClient` |
| Dedicated test server with the mod | `gradlew :mod:runServer` |

Tests:

- `QuicPacketsTest` checks the Initial-packet parsing against RFC 9001 vectors and real aioquic Initials
  (`relay/src/test/resources`).
- `RelayEndToEndTest` runs relay, tunnel and a fake game server on loopback.

Verified by hand (2026-10-12): aioquic completed a full QUIC + mutual-TLS handshake (ALPN `hytale/3`) with a real
0.7.0 dedicated server through a local relay and the mod.
