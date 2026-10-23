Krypton Hybrid
====
[![](https://badges.moddingx.org/curseforge/downloads/1474749)](https://www.curseforge.com/minecraft/mc-mods/krypton-hybrid)
[![License: LGPL-3.0](https://img.shields.io/badge/License-LGPL--3.0-blue.svg)](LICENSE)

[![curseforge](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/curseforge_vector.svg)](https://www.curseforge.com/minecraft/mc-mods/krypton-hybrid)
[![github](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/github_vector.svg)](https://github.com/dreamforSh/KryptonHybrid)

## Overview
Krypton Hybrid is a fork based on [Krypton fnp](https://github.com/404Setup/KryptonFNP) legacy（1.19.2),Completed the missing optimizations in the Krypton fnp lite version, added support for the zstd compression algorithm, and made improvements to the original encoding and Netty program processing.while retaining the features of [Krypton fnp](https://github.com/404Setup/KryptonFNP) and RecastLib.

## Differentiation
1. Compatibility support for hybrid servers is offered.

2. Commands are provided to monitor traffic usage and security status.

3. This project prioritizes using zstd as the primary compression algorithm, while also providing optimizations for entity tracking and communication encoding from the original version.

4. Built-in network security and DDoS protection layer — connection rate limiting, stage-aware timeouts, packet size and payload validation, anomaly detection, and Netty resource protection.

## Technical Details

### 1. Network Compression Pipeline

Krypton Hybrid replaces Minecraft's default zlib codec by injecting into `Connection.setupCompression`.


| Feature | Detail |
|---|---|
| Primary algorithm | Zstd via `zstd-jni` (native) |
| Fallback algorithm | zlib via `velocity-native` |
| Level range | 1 – 22 (default 3) |
| Multi-threaded | `workers` ≥ 1 activates native thread pool |
| Dictionary | Optional `.zdict` pre-trained file, used for packets up to `dict_max_packet_bytes` (larger ones are compressed without it). Load failures are logged; with `dict_required` Zstd is not offered (zlib), otherwise plain Zstd is used. See [Zstd dictionary training guide](docs/zstd-dictionary-training.md). |
| Advanced tuning | `overlap_log`, `job_size`, `strategy`, `LDM` + `window_log` |
| Batch frames | Optional (`batch_frames.enabled`, off by default): the server sends all packets of one flush to a Krypton Hybrid client as one Zstd frame |

#### Batch frames

Without batching every packet is its own frame, and packets below the compression threshold (256 bytes by default; entity movement, block updates, …) are sent uncompressed. With `batch_frames.enabled` the server holds the packets written between two flushes and compresses them together into one frame; the client splits it back into single packets before vanilla's packet decoder, so the rest of the pipeline and other mods still see one packet at a time, in order. With `tick_flush` that is one frame per player per tick.

- Server → client only, and only to clients that agreed during login (Krypton Hybrid with Zstd, same dictionary, no proxy).
- A packet flushed on its own, a packet above `max_packet_bytes`, and chunk / light packets at or above the threshold (served by the compressed broadcast cache) leave as single frames, after the packets held before them.
- A batch is capped at `max_batch_bytes` uncompressed; if compressing it would not beat sending its packets as single frames, they are sent as single frames.
- `/krypton stats` shows how many batch frames were sent and how many packets they carried.

#### Negotiation

The algorithm is negotiated per connection during login, with a vanilla login custom query that the server sends right before the compression packet (NeoForge negotiates its channels only later, in the configuration phase, after compression is already on). Both sides offer a token naming Zstd, the dictionary id and batch-frame support; Zstd is used only when the tokens match. A vanilla client answers that it does not know the query, and behind a proxy (`proxy.mode` other than `NONE`) the server does not ask, so those connections use vanilla zlib.

---

### 2. Light Payload Optimization — Uniform-RLE

Sky-light sections above the surface are almost always uniform (`0xFF` = all-15). Instead of writing 2 048 bytes per uniform layer, Krypton encodes them as 2 bytes.

**Wire format** (`ClientboundLightUpdatePacketData`):

```
[0x4B]            – Krypton marker (1 byte)
[skyYMask]        – BitSet
[blockYMask]      – BitSet
[emptySkyYMask]   – BitSet
[emptyBlockYMask] – BitSet
[skyCount]        – VarInt
For each sky DataLayer:
  [0x01] + 1 byte     – uniform (all nibbles == byte)
  [0x00] + 2048 bytes – raw    (fixed size, no VarInt prefix)
[blockCount]      – VarInt
For each block DataLayer: same as above
```

**Savings guard:** only activates when `2048 × uniform_count + raw_count − 1 > 0`.
**Max saving:** ~40 KB per chunk in open-sky environments (view-distance 12 → up to 20 uniform sky layers).

---

### 3. Chunk Data Optimization — XOR-Delta Heightmaps + Biome Stream

**Wire format** (`ClientboundLevelChunkPacketData`):

```
[0x4B]            – Krypton chunk-data marker (1 byte); 0x4C = byte-aligned block data

──── Heightmaps (binary + XOR-delta) ────
VarInt  entry_count               (0 = client computes them)
Per entry:
  UTF-8  key
  VarInt long_count
  long[] XOR-delta encoded  (delta[i] = raw[i] ^ raw[i-1])

──── Block data (sections without biomes) ────
VarInt  blocks_length
byte[]  blocks  (short nonEmptyCount + PalettedContainer<BlockState> per section)

──── Biome data (compact stream) ────
VarInt  section_count
Per section:
  0x01 + VarInt(biomeId)          – single-value section (2 bytes vs. 3+)
  0x00 + VarInt(len) + raw bytes  – multi-value section

──── Block entities ────
LIST_STREAM_CODEC (unchanged vanilla encoding)
```

| Optimization | Mechanism | Benefit |
|---|---|---|
| XOR-delta heightmaps | Adjacent columns correlate → many near-zero longs | Better Zstd/zlib ratio + ~40 B NBT overhead eliminated |
| Biome stream split | Biome data grouped; single-value sections compacted | Single-value: 2 B vs. 3+ B; cross-section redundancy exploited |
| Byte-aligned block data (`0x4C`) | 5–8-bit sections: one byte per palette index instead of bit-packed longs; 4-bit sections: longs little-endian | Indices no longer straddle bytes, so the compressor finds repeated patterns |

**Byte-aligned block data.** Vanilla packs block-state palette indices into longs; indices wider than 4 bits straddle byte boundaries, so the same block pattern becomes different bytes depending on where it starts. For clients that advertise it (`FEATURE_CHUNK_ALIGNED_BLOCKS` in the hello), the server rewrites each section's block data: 5–8-bit sections as 4 096 raw index bytes, 4-bit sections as little-endian longs (indices in byte order); single-value and global-palette sections are unchanged. The client restores vanilla longs before `LevelChunkSection.read()`. On a test world (973 overworld chunks) this made chunk packets 7.5 % smaller after Zstd level 6 (6.8 % with zlib) for about 0.04 ms of extra work per chunk on each side. Older clients keep receiving `0x4B`.

**Client-computed heightmaps.** The client's `WORLD_SURFACE` and `MOTION_BLOCKING` heightmaps follow from the block data, so for clients that advertise `FEATURE_CHUNK_CLIENT_HEIGHTMAPS` the server sends an empty heightmap list. The client scans each column top-down with the heightmaps' own block predicates, writes the result in vanilla's raw format and hands it to the vanilla packet constructor, so `LevelChunk.replaceWithPacketData` applies it as usual. On the test world the computed `WORLD_SURFACE` matched the server's saved heightmap in all 973 chunks, took about 0.08 ms per chunk, and leaving the heightmaps out made chunk packets another 6.6 % smaller.

---

### 4. Delayed Chunk Cache (DCC)

Departing chunks the client actually received (recorded when `PlayerChunkSender` sends or forgets them) are **kept on the client** instead of immediately dropped, as long as they stay within the client's chunk storage (view distance + 3 around its view center); state is tracked per connection, so it follows respawns and resets when the client changes dimension. If the player re-enters range before the entry expires **and the chunk has not changed since it left the player's view**, the full resend is skipped. Every chunk carries a change stamp (bumped on block changes, including in non-ticking chunks, and on every block / light / block-entity broadcast); a changed chunk is resent normally.

| Config key | Default | Description |
|---|---|---|
| `dcc.enabled` | `true` | Master switch |
| `dcc.size_limit` | 60 | Max buffered chunks per player |
| `dcc.timeout_seconds` | 30 | Forced eviction timeout |

Eviction occurs on three dimensions: **distance** (before the player's view center moves, so the client still applies the forget), **capacity**, and **timeout** (on `ChunkMap.tick()`).

---

### 5. Flush Consolidation + Tick Packet Batching

Each server tick's packets are written to every player's connection and flushed once, instead of one flush per packet. With `tick_flush.enabled` (default) the hold covers the **whole server tick** — world, entity, block-entity and player ticks — and each connection is released by vanilla's own per-connection flush in the connection phase. Replies to client packets between ticks are not held. Inside the tick the broadcast phase still collects and coalesces packets per player:

```
MinecraftServer.tickServer() HEAD
  Hold flushes for all players (whole-tick window)

ServerChunkCache.tickChunks() "broadcast" phase
  ① Recovery: deliver anything a failed previous tick left behind
  ② Hold flushes for all players (outermost window)
  ③ Collect block / light / block-entity updates per player

ChunkMap.tick() (entity tracking, right after tickChunks, still inside the window)
  ④ Write the collected block updates first (vanilla order)
  ⑤ Collect entity updates per player, coalesce them, write them

ServerChunkCache.tick(), after ChunkMap.tick()
  ⑥ Without tick_flush: release the hold → one flush per player (or after micro_batch_flush.delay_ms)

Connection.tick() (connection phase)
  ⑦ With tick_flush: release this player's hold → one flush for the whole tick
```

Window state changes reach each connection's event loop as tasks queued behind the writes, so packets sent from the server thread are batched even when their writes run after the window closes. Chunk batches sent by `PlayerChunkSender` and the packets of `ServerEntity.addPairing` only hold the flush themselves when no window already does.

| Layer | Mechanism | Benefit |
|---|---|---|
| Flush consolidation (Netty) | Buffer writes, one `flush()` per player per tick | Fewer syscalls on busy servers |
| Packet coalescing | Drop superseded motion / teleport / entity-data / head-rotation packets in the same tick | Fewer packets and bytes |
| Motion delta filter | Drop velocity packets that barely changed since the last one sent to that player (a stop is always sent) | Fewer projectile / item packets |
| Bundle coalescing (protocol) | A player's collected packets of one tick → 1 `ClientboundBundlePacket`; with `bundle_compression`, its sub-packets are compressed from `min_bytes` up | Client applies the tick's updates together; small sub-packets compressed |

The dashboard reports **flush savings** (share of flushes avoided, `1 − flushes / packets held`) and **packets per flush**.

---

### 6. Hot-Path Micro-Optimizations

| Target | Optimization |
|---|---|
| `VarInt.read()` | Branchless 4-byte `getIntLE` + bit-twiddling fast path (covers values 0 – 268 M) |
| `VarInt.getByteSize()` | Lookup-table via `Integer.numberOfLeadingZeros` |
| `VarLong.getByteSize()` | Same lookup-table approach |
| `FriendlyByteBuf.writeVarInt()` | Peeled 1/2/3/4/5-byte paths, writes directly to backing `ByteBuf` |
| `FriendlyByteBuf.writeUtf()` | Single-pass via `ByteBufUtil.utf8Bytes` — no intermediate `byte[]` |
| `Varint21FrameDecoder` | Velocity-derived efficient framing + nullping attack mitigation |

---

### 7. Observability — `/krypton` Commands

```
/krypton stats show          – compression ratio, bandwidth saving, uptime
/krypton stats reset         – reset all counters
/krypton packets bycount [n] – top N packet types by count
/krypton packets bybytes [n] – top N packet types by bytes
/krypton mods bycount [n]    – top N mod namespaces by count
/krypton mods bybytes [n]    – top N mod namespaces by bytes
/krypton security status     – security metrics snapshot (rate limits, validation, anomalies, etc.)
```

Requires operator permission level 2.

---

### 8. Network Security & DDoS Protection

Krypton Hybrid includes a comprehensive, configurable network security layer injected directly into the Netty pipeline. All features are gated behind a single `security.enabled` toggle, **off by default**, and can be fine-tuned via the `[security]` section in `krypton_hybrid-common.toml`. Tune the per-IP limits and timeouts for your network setup (proxies, tunnels, shared NAT addresses) before turning it on. Optimizations such as the broadcast serialization cache and the MOTD cache do not depend on this toggle.

#### Netty Pipeline (Security Handlers)

```
[Socket]
  → krypton_rate_limiter      Per-IP sliding-window connection rate limiter
  → krypton_timeout           Stage-aware read timeout (HANDSHAKE → LOGIN → PLAY)
  → timeout (vanilla)
  → legacy_query (vanilla)
  → splitter (Varint21)
  → krypton_size_validator    Decoded frame size validation
  → decoder
  → prepender
  → encoder
  → krypton_resource_guard    Write watermarks + pending queue protection
  → packet_handler (Connection)
```

#### Security Modules

| Module | Class | Description |
|---|---|---|
| **Connection Rate Limiter** | `ConnectionRateLimiter` | Per-IP sliding-window counter. Closes new connections exceeding the burst limit (default: 20/s). `@Sharable` singleton — zero per-connection allocation. |
| **Handshake Timeout** | `HandshakeTimeoutHandler` | Stage-aware `ReadTimeoutHandler`: HANDSHAKE 5s → LOGIN 30s → PLAY 30s. Prevents half-open connection exhaustion. |
| **Packet Size Validator** | `PacketSizeValidator` | Post-frame-decode size check (default max: 2 MiB). Rejects empty frames and force-closes on severely oversized packets (>4× limit). |
| **Handshake Validator** | `HandshakeValidator` | Validates server address length/content and port range. Rejects scanning bots and malformed clients at earliest stage. |
| **Anomaly Detector** | `AnomalyDetector` | Per-connection weighted strike system. Tracks HMAC failures (weight 3), compression errors (2), protocol violations (3), invalid packets (2), missing hello (1). Disconnects at threshold (default: 10 strikes). |
| **Netty Resource Guard** | `NettyResourceGuard` | Configures write buffer watermarks (512 KiB / 2 MiB). Limits pending write queue (default: 4096). Force-closes channels unwritable for too long (default: 15s). Prevents slow-read memory exhaustion. |
| **Security Metrics** | `SecurityMetrics` | Lock-free `AtomicLong` counters for all security events. Periodic summary logging (default: every 5 min). Exposed via `/krypton security status`. |

#### Configuration (`[security]` section)

```toml
[security]
  enabled = false                           # Global kill-switch (off by default)

  [security.connection_rate_limit]
    rate = 8                                # Per-IP sustained connections/s
    burst = 20                              # Per-IP burst limit

  [security.decompression_guard]            # Checked by the server on client packets only
    max_decompressed_bytes = 16777216
    max_ratio = 100

  [security.timeouts]
    handshake_sec = 5
    login_sec = 30                          # Mojang auth + compression negotiation (vanilla: 30s)
    play_sec = 30

  [security.packet_size]
    frame_max_bytes = 2097152               # 2 MiB max decoded frame

  [security.handshake_validation]
    max_address_length = 255

  [security.anomaly]
    strike_threshold = 10                   # Weighted strikes before disconnect

  [security.resource_guard]
    watermark_low = 524288                  # 512 KiB
    watermark_high = 2097152               # 2 MiB
    max_pending_writes = 4096
    max_unwritable_seconds = 15
```

## test data
In the original test, this mod was able to reduce the initial package size to 13% of the original.

<img src="./test.png" alt="test" />

## Credit

- [Krypton Fabric](https://modrinth.com/mod/krypton)
- [Velocity](https://github.com/PaperMC/Velocity)
- [VelocityNT Recast](https://github.com/404Setup/VelocityNT-Recast)
- [Paper](https://github.com/PaperMC/Paper)
- [RecastSSL](https://github.com/404Setup/RecastSSL)
- [NotEnoughBandwidth](https://github.com/USS-Shenzhou/NotEnoughBandwidth)
