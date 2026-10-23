# <img src="docs/assets/icons/history.svg" width="26" height="26" alt=""> Changelog

<p align="right"><img src="docs/assets/icons/globe.svg" width="14" height="14" alt=""> <a href="CHANGELOG.md">简体中文</a> | <b>English</b></p>

## [0.3.10] - Unreleased

For Minecraft 1.21.1 / NeoForge 21.1. Previous version: 0.2.33. Brings the work of the 1.19.2 and 1.20.1 branches to this version (including batch frames, byte-aligned chunk block data and client-computed heightmaps); where 1.21.1 / NeoForge needed a different implementation, it is noted.

### <img src="docs/assets/icons/warning.svg" width="18" height="18" alt=""> Upgrade notes

- **Update the server and the clients together.** Compression is now negotiated during login, and the Krypton payload channel version went from 1 to 2. Older Krypton Hybrid builds cannot join this version (they are disconnected during login when the compression does not match, otherwise refused during configuration for the channel version); they never misparse packets.
- **`security.enabled` now defaults to `false`.** Security features are opt-in. NeoForge keeps the values of existing config files, so existing servers are unchanged; on a new server, turn it on when you need it, after tuning the per-IP limits and timeouts for your network (proxies, tunnels, shared NAT).
- **Behind a proxy, set `proxy.mode` to `VELOCITY`** (for Velocity and BungeeCord alike). The backend then always uses ZLIB and skips the per-IP connection and status-ping limits, since every player arrives from the proxy's address. `AUTO` has no detection yet and behaves like `VELOCITY`.
- The default changes listed below only apply to newly generated config files; edit existing files as needed.

### <img src="docs/assets/icons/added.svg" width="18" height="18" alt=""> Added

- **Compression negotiation**: the compression algorithm and dictionary are negotiated during login. Zstd is used only when both sides run Krypton Hybrid, both chose ZSTD, the dictionary IDs match and `proxy.mode = NONE`; otherwise the connection falls back to vanilla ZLIB. Vanilla clients and proxies always use ZLIB. After a config change or a dictionary reload, the new token applies to every player who logs in afterwards.
  - NeoForge negotiates its channels in the configuration phase, after compression is already on, so the negotiation uses vanilla's login custom query: before sending the compression packet, the server asks the client for its compression token and waits for the answer. A vanilla client answers that it does not know the query and gets ZLIB.
- **Batch frames** (`batch_frames.enabled`, off by default): the server compresses all packets of one flush to a player into a single Zstd frame instead of one frame per packet. Small packets below the compression threshold, which were sent uncompressed (entity movement, block updates, ...), are now compressed together.
  - Server to client only, and only to clients that agreed during login (Krypton Hybrid with ZSTD, same dictionary, no proxy).
  - The client splits the frame back into single packets before vanilla's packet decoder, so the rest of the pipeline and other mods still see one packet at a time, in order.
  - A packet flushed on its own, a packet above `max_packet_bytes`, and chunk / light packets at or above the compression threshold still get their own frame, after the packets held before them, so the compressed broadcast cache keeps working.
  - A batch holds at most `max_batch_bytes` uncompressed; if compressing it together would not be smaller than sending its packets one by one, they are sent one by one.
  - `/krypton stats` has a new line with the number of batch frames and the packets they carried.
  - With the whole-tick flush, each player gets one batch frame per tick.
- **Byte-aligned chunk block data** (part of `chunk_data_opt.enabled`): vanilla packs block palette indices bit by bit into long arrays; indices of 5 bits or more straddle bytes, which hides repetitions from the compressor. Sections with 5–8-bit indices now store one byte per index and 4-bit sections use little-endian longs; single-value and global-palette sections are unchanged. The client restores the vanilla format before reading.
  - On 973 chunks of a test world, chunk packets got 7.5 % smaller after Zstd level 6 (6.8 % with zlib), for about 0.04 ms of conversion per chunk on each side.
  - Negotiated with a new feature flag in the configuration-phase hello: the server sends the new format (chunk marker `0x4C`) only to clients that declare support.
- **Client-computed heightmaps** (part of `chunk_data_opt.enabled`): for clients that declare support, the server leaves the `WORLD_SURFACE` and `MOTION_BLOCKING` heightmaps out of chunk packets (sends an empty list). After reading the sections, the client computes them by scanning each column top-down with the heightmaps' own block predicates and hands them to vanilla in vanilla's format.
  - On the same 973 chunks the computed `WORLD_SURFACE` matched the server's saved heightmap exactly, at about 0.08 ms per chunk; chunk packets got another 6.6 % smaller.
  - Also negotiated with a new feature flag. The hello's feature flags are now a VarInt, which encodes the existing flags exactly like the old single byte.
- **Redesigned stats dashboard**: opened with `/krypton stats gui` or a key binding, a translucent floating panel with Overview, Traffic, Mods and Security tabs (number keys 1–4).
  - Overview: live bandwidth saving, outbound and inbound rates, packet rate, outbound traffic chart, the mod with the most traffic.
  - Traffic: packet rate chart, outbound and inbound details, flush saving, coalesce drop rate, share of classified traffic.
  - Mods: a filterable, sortable per-mod traffic table.
  - Security: all security counters, plus this client's round-trip time and requests.
  - The title bar shows the compression this connection actually negotiated (ZSTD, ZLIB, uncompressed or local).
  - Refreshes every second while open; dark and light themes and adjustable panel opacity.
  - Rendered in batches through the 1.21 vertex API; chart geometry is cached on the GPU and only rebuilt when the data or the layout changes.
- **HUD improvements**: live rate sparklines, latency, background opacity, any of the four corners, refresh interval, and a toggle for the top-mod line.
- **Client config file** `krypton_hybrid-client.toml`: dashboard and HUD preferences are saved automatically.
- **Visual config editor**: opened from "All settings" on the dashboard's settings page, or with this mod's "Config" button in the mod list. Controls are generated from the config spec: switches for booleans, segmented choices for enums, sliders for small integer ranges, validated text fields for large numbers and text, masked fields for secrets. Includes search, per-option reset and reset-all.
- **Server owners can edit the server config remotely**: on a dedicated server, players with permission level 4 get a "Server" page in the config editor that edits the server's `krypton_hybrid-common.toml`.
  - The server checks the permission again, applies changes at once, saves them to disk and logs who changed what.
  - Dedicated servers only: on a LAN world with cheats every guest has level 4, and the host edits the config locally anyway.
  - Options naming files or directories, and sample capture, are read-only remotely; they decide which files the server reads and writes.
  - Options whose name contains `secret` are write-only: their values are never sent to clients or written to the log.
- **Whole-tick flush window** (`tick_flush.enabled`, on by default): everything a player receives during one server tick (world, entity, block-entity and player ticks) goes out in one flush, in vanilla's connection phase. Replies to client packets between ticks are not held.
- **Native encryption on the client**: the client also uses the native in-place cipher instead of the Java cipher, which allocates and copies on every read. Falls back to vanilla if the native cipher cannot be initialized.
- `/krypton stats` has new lines for flush consolidation, batch frames and broadcast cache hit rates (serialization and compression); `/krypton stats reset` also clears the cache counters.
- Two new security counters: dropped status pings and dropped legacy pings.

### <img src="docs/assets/icons/zap.svg" width="18" height="18" alt=""> Performance

- **Delayed chunk cache (DCC) rewrite**:
  - State is kept per connection, survives respawns and is cleared on a dimension change.
  - Only chunks the client actually received and can still keep (within its view distance + 3) are kept. What was sent and forgotten is taken from the chunk and forget packets `PlayerChunkSender` actually sent.
  - Every chunk carries a change stamp. When a player returns, the resend is skipped only if the chunk did not change while they were away.
  - `dcc.distance` is gone; the range follows the client's chunk storage automatically.
- **Zstd encoding**:
  - The dictionary is only used for small and medium packets; packets above `dict_max_packet_bytes` (such as chunk data) are compressed without it.
  - The default of the large-packet level `zstd_adaptive_large_level` went from 11 to 6; when the network threads are busy (many players joining at once) it falls back to the base level.
  - Dictionary frames omit the magic number and the dictionary ID, saving a few bytes per packet.
- **Zstd decoding**: heap input is decompressed straight from its backing array instead of being copied first.
- **Frame length written in place**: the compression encoders reserve room for the frame header, and `Varint21LengthFieldPrepender` writes the length into it, saving a buffer allocation and a copy of the whole frame.
- **Entity data throttle**: item stacks are compared by their serialized bytes and not resent when unchanged (vanilla compares item stacks by reference, so a copy with the same content is sent again). Every other value is sent as in vanilla, because vanilla resends equal values on purpose: forced restarts of display interpolation, and `stopFallFlying` setting and clearing a flag within one tick to correct the client's elytra state.
- **Broadcast caches**:
  - Entries of the serialization and compression caches only live for one tick.
  - The compression cache is keyed by dictionary, so bytes compressed with an old dictionary are not reused after a reload.
  - Uncompressed raw output is no longer cached.
- Per-packet-type and per-mod stat keys are computed once per packet class and channel and then cached, instead of on every encode.
- The motion filter's state is cleared when a player starts or stops tracking an entity, so it no longer grows for the whole session.
- Stats snapshots are requested with a dedicated payload instead of the `/krypton stats gui` command; the server limits requests per connection (500 ms) and serves requests within 250 ms from one shared snapshot. The client does not send requests to servers without the channel.

### <img src="docs/assets/icons/fixed.svg" width="18" height="18" alt=""> Fixed

- **Vanilla clients disconnected on mixed servers**: the broadcast serialization cache handed bytes encoded for one connection to other connections on the same network thread. Light updates encoded in Krypton's format for a Krypton client reached vanilla clients, which failed to decode them and disconnected. Cached bytes now carry the encoding signature of the connection (NeoForge connection type and the peer's hello flags) and are only reused for the same signature.
- **Disconnect on bundles with too many packets**: the packets collected in one tick are split into bundles of at most 4096. The client disconnects on a larger bundle, for example when thousands of signs change in one tick.
- **Security read limits**:
  - They only apply while the server decodes packets from clients. They also ran on clients, where the login disconnect reason (`readUtf(262144)`) and the registry sync of large modpacks were rejected, so players could not join.
  - They check the length or count the peer actually sent, instead of the maximum the caller declared; before, they rejected legitimate calls and never caught oversized data.
  - Most 1.21.1 packets decode through `StreamCodec`s; those paths (`Utf8String.read`, `ByteBufCodecs.readCount`, the static `readByteArray`) are checked too.
- **Write watermarks**: a low watermark above the high one is lowered to the high one. Netty used to throw for every new connection, so nobody could join and every attempt leaked a Connection that was never removed; editing the options one at a time easily passed through such a state.
- **Handshake deadline**: the handshake stage (status pings never leave it) lasts at most twice `handshake_sec`. The read timeout only measures silence, so a connection sending a byte every few seconds could stay open forever.
- **Per-IP limits**: IPv6 sources are counted by their /64 prefix; a host holding a /64 could otherwise get a fresh budget from every new address. The connection and status-ping limiters each track at most 65536 addresses.
- **Replaced block entities not updated**: when a block entity was removed and placed again at the same position (a banner with the same pattern, a skull, ...), the client had a fresh default object, but the server compared against the old tag, found no change and sent nothing. The cache is now tied to the block entity object, and an empty tag also resets the position's delta base.
- **Flush consolidation**:
  - The packets collected in one tick are still sent as one `ClientboundBundlePacket`, and each player's flushes are consolidated.
  - On 1.21.1, entity tracking (`ChunkMap.tick()`) runs after `tickChunks` returns; the per-level flush window now stays open until entity tracking is done, so entity packets share the same flush.
  - Fixed nested windows switching auto-flush back on early, after which every packet was flushed on its own. `PlayerChunkSender` no longer releases the whole-tick window early when sending chunk batches.
  - Packets sent from other threads are included in the same flush.
  - The stat changed from "bundles" to "flush consolidation", showing packets per flush and the saving. `bundle_compression` (forced compression of bundle sub-packets) is kept.
- **Block entity deltas**:
  - Deltas are now sent after the block updates of the same tick. The client could receive data before the block entity existed and lose it.
  - The client keeps a delta base per position, merges the delta back into the full tag and applies it on the client thread through NeoForge's `onDataPacket`, which keeps mods that read their update tag themselves working.
  - The delta cache of a chunk is cleared when the chunk is sent again (reload, dimension change, respawn).
  - Packet coalescing no longer replaces a packet with a delta that depends on it.
- **Delayed chunk cache**:
  - Chunks that changed while the player was away could stay unsent on return, so the client showed old blocks. Block-entity-only, light and biome changes are now stamped too when no other player watches the chunk.
  - "Forget chunk" packets are sent before the new view center, so the client can still apply them.
  - Nothing is kept when a player disconnects, changes dimension or respawns; kept chunks are dropped normally, so NeoForge's `ChunkWatchEvent` watch and unwatch events stay paired.
- **Chunk loading in singleplayer and on LAN hosts**: the memory connection gets every ready chunk at once, as in vanilla, instead of following the network batch quota.
- **Motion filter**:
  - A velocity of 0 is always sent, so entities no longer keep drifting on the client.
  - Teleports are no longer filtered across ticks (`motion_delta.teleport_delta_squared` removed); duplicate teleports within a tick are still removed by packet coalescing.
- **Legacy ping guard**: only reads starting with the legacy ping byte `0xFE` are counted. The first read of every new connection, including normal handshakes, used to cost status-ping budget.
- **Connection rate limiter**:
  - A limited connection is closed only after vanilla's `Connection` has finished setting up, which fixes an NPE on disconnect and connections that were never removed.
  - Loopback connections are no longer limited per IP.
  - At most one rate-limit warning per IP every 5 seconds.
  - Entries of IPs idle for over 60 seconds and not quarantined are removed, which fixes memory growth on long-running servers. The status-ping limiter does the same.
- **Write protection**: bursts while joining, teleporting or around dense entities no longer fail writes and kick healthy players. Only a connection with more than `max_pending_writes` pending writes that has also been unwritable for longer than `max_unwritable_seconds` is closed as a slow reader (once). Also fixed listeners added to void promises.
- **Decompression bomb guard**:
  - Only the server checks packets from clients. Clients used to disconnect on highly compressible packets from the server (uniform light, map data).
  - The Java fallback decompressor's output buffer is bounded, and the decompressed size is checked against the declared one.
- **Login timeout**: the default went from 10 to 30 seconds, covering Mojang authentication and the compression negotiation (vanilla allows 30 seconds); NeoForge's registry sync happens later, during configuration, under `play_sec`. Also fixed an installed timeout handler staying on the 5-second handshake timeout after security was turned off.
- The broadcast serialization cache and the MOTD cache no longer depend on `security.enabled`; the broadcast cache is only used for server-to-client play packets.
- Receiving a stats snapshot no longer opens the dashboard; only `/krypton stats gui` does. Stats of a server are cleared when leaving it.
- With `zstd_advanced.dict_required = true` and a dictionary that fails to load, Zstd context creation no longer fails; Zstd is simply not offered and connections fall back to ZLIB.
- If the negotiated dictionary becomes unavailable because the config changed during a login, the connection is closed instead of sending data the peer cannot decode.

### <img src="docs/assets/icons/sliders.svg" width="18" height="18" alt=""> Config changes

| Option | Change |
|---|---|
| `security.enabled` | Default `true` → `false` |
| `security.timeouts.login_sec` | Default 10 → 30 |
| `compression.zstd_adaptive_large_level` | Default 11 → 6 |
| `zstd_advanced.dict_max_packet_bytes` | New, default 16384 |
| `zstd_advanced.dict_required` | A dictionary that fails to load now falls back to ZLIB instead of an error |
| `zstd_advanced.dict_training_samples_dir` | Default `run/krypton_zstd_samples` → `krypton_zstd_samples`; relative paths start at the game directory |
| `batch_frames.enabled` | New, default `false` |
| `batch_frames.max_packet_bytes` | New, default 16384 |
| `batch_frames.max_batch_bytes` | New, default 262144 |
| `tick_flush.enabled` | New, default `true` |
| `micro_batch_flush.*` | Only applies when `tick_flush` is off |
| `proxy.mode` | Anything but `NONE` also skips the per-IP limits |
| `dcc.distance` | Removed |
| `motion_delta.teleport_delta_squared` | Removed |
| `security.packet_size.read_limits_enabled` | Only applies to packets the server receives, and checks actual lengths |
| `proxy.forwarding_secret` | The comment now says the option is not used (it claimed HMAC verification); a warning is logged when it is set |

New client file `krypton_hybrid-client.toml` with `dashboard.theme`, `dashboard.panel_opacity` and `hud.*` (`visible`, `anchor`, `refresh_ms`, `show_top_mod`, `show_graph`, `show_latency`, `background_opacity`).

### <img src="docs/assets/icons/book.svg" width="18" height="18" alt=""> Zstd dictionary training

- Sampling only records packets that are actually compressed (at or above the compression threshold), each packet object once, and none of Krypton's own channels. The write queue is capped by bytes.
- The training set concentrates on the packet sizes the dictionary is used for; large packets are only added in a set share, and duplicate samples are removed. The evaluation set is held out by file name, and evaluation measures the encoder's actual wire format.
- The trainer takes named arguments (`--samples`, `--output`, `--dict-size`, ...). The Gradle task `trainZstdDict` gained `-PlargeSampleShare` and `-PevalShare`; the default output is `run/config/krypton_hybrid.zdict`.
- New guide: [docs/zstd-dictionary-training.md](docs/zstd-dictionary-training.md).

### <img src="docs/assets/icons/code.svg" width="18" height="18" alt=""> Development

- JUnit tests for the chunk codec (byte alignment, heightmap computation), batch frames and bundle splitting; `gradlew test` runs them without starting the game.

### <img src="docs/assets/icons/package.svg" width="18" height="18" alt=""> Dependencies

- zstd-jni 1.5.7-7 → 1.5.7-24
