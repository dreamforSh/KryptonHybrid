# Krypton Hybrid Zstd 字典训练与应用指南

Krypton Hybrid 支持为 Zstd 压缩加载预训练字典。字典的作用是让**中小数据包**（几百字节到十几 KB，例如实体同步、方块更新、配方/标签同步和模组 payload）压缩得更小；区块这类大包本身已经足够长，字典帮不上忙，所以默认只对不超过 `dict_max_packet_bytes`（16 KB）的包使用字典。

重要限制：

- 服务端和客户端必须同时安装 Krypton Hybrid，并使用**同一个字典文件**。字典不一致时两端会协商失败，改用 zlib，不会断线。
- 使用字典的连接采用新的帧格式（带字典的包用不含魔数的帧，大包用普通帧）。对端如果是不支持这种格式的旧版本，两端同样会改用 zlib。
- 上线前建议先在测试服验证。

## 1. 采集训练样本

样本采集默认关闭。只在测试窗口临时打开，采够样本后立刻关掉。

编辑 `config/krypton_hybrid-common.toml`：

```toml
[compression]
algorithm = "ZSTD"

[zstd_advanced]
dict_training_capture_enabled = true
dict_training_samples_dir = "krypton_zstd_samples"
dict_training_max_samples = 12000
dict_training_sample_every = 3
dict_training_min_bytes = 24
dict_training_max_bytes = 262144
```

采集规则：

- 只采**实际会被压缩**的包（不低于服务器的压缩阈值 `network-compression-threshold`，默认 256 字节）；更小的包原样发送，永远用不上字典。
- 同一个数据包对象只采一次（广播给多个玩家的包不会被重复采集）。
- 不采集 Krypton Hybrid 自己的频道（统计、配置同步）。
- 只在使用 Zstd 的连接上采集。连接改用 zlib 时（例如对端没有安装本模组）不会产生样本。
- 写盘队列最多占用 32 MB 内存，磁盘跟不上时会跳过样本而不是继续堆积。

`dict_training_samples_dir` 的相对路径从**游戏目录**算起：专用服务器就是服务器根目录，开发环境是 `run/`（样本会写到 `run/krypton_zstd_samples`）。

启动服务端并制造真实流量：

- 让玩家登录、传送、探索新区块。
- 打开常用模组 GUI，触发配方、任务、地图、同步类功能。
- 覆盖服务器的常见玩法场景（农场、红石、战斗），而不是只站在出生点。玩家刚进服时几乎全是区块包，只采进服那一小段是不够的。

运行中可查看状态：

```mcfunction
/krypton zstd status
```

`Capture:` 一行会显示样本目录、已保存数量、已看到的符合条件的包数和写入失败数。

采样文件扩展名为 `.pkt`，是压缩前的序列化网络包，可能包含模组 payload 数据，不建议公开发布。

采够后关闭采集：

```toml
[zstd_advanced]
dict_training_capture_enabled = false
```

然后重启或重载配置。

## 2. 训练字典

在项目根目录运行（开发环境的样本目录是 `run/krypton_zstd_samples`，输出默认写到 `run/config/krypton_hybrid.zdict`）：

```powershell
.\gradlew.bat trainZstdDict "-PsamplesDir=run/krypton_zstd_samples" "-PoutputDict=run/config/krypton_hybrid.zdict"
```

样本来自正式服务器时，把整个样本目录复制到本机，再把 `-PsamplesDir` 指向它。

可选参数（不填时使用括号里的默认值）：

| 参数 | 默认 | 说明 |
|---|---|---|
| `-PdictSize` | 65536 | 字典大小（字节） |
| `-PmaxSamples` | 8000 | 最多使用的核心样本数 |
| `-PminSampleBytes` | 256 | 核心样本下限，应与服务器的压缩阈值一致 |
| `-PmaxSampleBytes` | 16384 | 核心样本上限，应与 `dict_max_packet_bytes` 一致 |
| `-PlargeSampleShare` | 0.25 | 额外加入的大样本（区块等）占核心样本数的比例 |
| `-PevalShare` | 0.2 | 留作评估、不参与训练的样本比例 |
| `-PevalSamples` | 1000 | 最多评估的样本数 |

训练器的做法：

- 小于 `minSampleBytes` 的样本直接跳过（它们不会被压缩）。
- 核心样本是 `minSampleBytes`..`maxSampleBytes` 之间的包；更大的包只按 `largeSampleShare` 的比例少量加入，避免区块包按字节数淹没其他样本。
- 内容完全相同的样本只用一次。
- 按文件名固定留出 `evalShare` 的样本**不参与训练**，训练完用它们评估。

训练器输出：

- 字典 ID、字典大小、Krypton 元数据包装后的文件大小。
- 核心样本、大样本、跳过的小样本、重复样本、留出样本的数量。
- **留出样本评估**：按实际发送方式计算（长度头 + 帧，压缩不划算时原样发送），分别给出 256B–1KB、1–4KB、4–16KB 三档在压缩级别 3 和 6 下"不用字典 / 用字典"的大小和变化。
- 字典在留出样本上节省不到 1% 时会给出警告。

建议：

- `dictSize=32768`：轻量，适合样本较少时。
- `dictSize=65536`：推荐默认值。
- `dictSize=131072`：样本很多且模组 payload 重复度高时再尝试。
- 样本总字节数至少应为字典大小的 8 倍，更理想是 50 到 100 倍。

## 3. 检查字典

```powershell
.\gradlew.bat inspectZstdDict "-PdictPath=run/config/krypton_hybrid.zdict"
```

没有字典 ID 的字典（ID 为 0，例如原始内容字典）以及元数据 ID 与字典本身不符的文件会被标记为 REJECTED，Krypton 不会加载它们。训练器生成的字典总是带 ID。

## 4. 应用字典

把同一个 `krypton_hybrid.zdict` 分发到：

- 专用服务器的 `config/krypton_hybrid.zdict`
- 每个客户端的 `config/krypton_hybrid.zdict`

然后服务端和客户端都配置：

```toml
[compression]
algorithm = "ZSTD"

[zstd_advanced]
dict_enabled = true
dict_path = "config/krypton_hybrid.zdict"
dict_required = false
dict_max_packet_bytes = 16384
```

- `dict_path` 的相对路径从游戏目录算起。
- 字典加载成功时日志里有 `Loaded Zstd dictionary: ...`；加载失败时会记一条 ERROR，说明原因和后果。
- `dict_required = true`：字典加载失败时本机不再提供 Zstd，所有连接改用 zlib。
- `dict_required = false`：字典加载失败时改用不带字典的 Zstd。
- `dict_max_packet_bytes`：大于这个大小的包不用字典压缩。训练时的 `maxSampleBytes` 应与它一致。

运行中可重载字典：

```mcfunction
/krypton zstd dict reload
/krypton zstd status
```

重载后，新登录的玩家会按新字典协商；已在线的连接继续使用原来的字典，直到重新连接。

想确认某个连接实际用的是 zstd 还是 zlib，可以把日志级别调到 DEBUG，查看 `Compression for <地址>: zstd/zlib (local=..., peer=...)`，其中 `local` 和 `peer` 是两端交换的协商标记。

## 5. 推荐上线流程

1. 在测试服打开 `dict_training_capture_enabled`。
2. 采集 10 到 30 分钟覆盖各种玩法的真实流量。
3. 关闭采集，训练字典，确认留出样本评估里各档都有明显收益。
4. 用测试客户端配置同一个字典并连接测试服，查看 `/krypton zstd status` 和日志。
5. 没有问题后，把字典和配置同步到生产服与客户端。

## 6. 回滚

如果出现问题，先关闭字典：

```toml
[zstd_advanced]
dict_enabled = false
dict_required = false
```

如果需要完全回到兼容模式：

```toml
[compression]
algorithm = "ZLIB"
```

## 7. 常见问题

`/krypton zstd status` 显示 `dict=error(...)`：
括号里是失败原因，日志里也有对应的 ERROR。检查 `dict_path` 是否正确，文件是否分发到了服务端和客户端同一路径。

样本目录一直是空的：
确认连接用的是 Zstd（对端也安装了本模组且算法为 ZSTD），并且有大于压缩阈值的包在发送。`/krypton zstd status` 的 `seen` 为 0 表示还没有符合条件的包。

训练器提示核心样本不足：
采集时间太短或基本只有进服时的区块包。多采集一段日常游玩的流量。

训练器提示字典收益低于 1%：
样本不够代表真实流量。重新采集覆盖更多玩法和模组同步场景，或尝试较小的 `dictSize`。

字典是否需要和配置一起发布：
需要。`dict_enabled=true` 时，字典文件本身就是连接协议的一部分，服务端和客户端必须使用同一份文件。
