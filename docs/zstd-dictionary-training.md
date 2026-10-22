# Krypton Hybrid Zstd 字典训练与应用指南

Krypton Hybrid 支持为 Zstd 压缩加载预训练字典。字典适合 Minecraft 网络包这种大量重复、结构稳定的流量，尤其是区块、光照、实体同步和模组自定义 payload。

重要限制：服务端和客户端必须同时安装 Krypton Hybrid，并使用同一个 Zstd 字典文件。字典不一致时，连接可能在解压阶段失败。上线前建议先在测试服验证。

## 1. 采集训练样本

样本采集默认关闭。只在测试窗口临时打开，采够样本后立刻关掉。

编辑 `config/krypton_hybrid-common.toml`：

```toml
[compression]
algorithm = "ZSTD"

[zstd_advanced]
dict_training_capture_enabled = true
dict_training_samples_dir = "run/krypton_zstd_samples"
dict_training_max_samples = 12000
dict_training_sample_every = 3
dict_training_min_bytes = 24
dict_training_max_bytes = 262144
```

启动服务端并制造真实流量：

- 让玩家登录、传送、探索新区块。
- 打开常用模组 GUI，触发配方、任务、地图、同步类功能。
- 覆盖服务器的常见玩法场景，而不是只站在出生点。

运行中可查看状态：

```mcfunction
/krypton zstd status
```

采样文件会写入 `run/krypton_zstd_samples`，扩展名为 `.pkt`。这些文件是压缩前的序列化网络包，可能包含模组 payload 数据，不建议公开发布。

采够后关闭采集：

```toml
[zstd_advanced]
dict_training_capture_enabled = false
```

然后重启或重载配置。

## 2. 训练字典

在项目根目录运行：

```powershell
.\gradlew.bat trainZstdDict "-PsamplesDir=run/krypton_zstd_samples" "-PoutputDict=config/krypton_hybrid.zdict" "-PdictSize=65536" "-PmaxSamples=8000"
```

可选参数：

```powershell
.\gradlew.bat trainZstdDict `
  "-PsamplesDir=run/krypton_zstd_samples" `
  "-PoutputDict=config/krypton_hybrid.zdict" `
  "-PdictSize=65536" `
  "-PmaxSamples=8000" `
  "-PminSampleBytes=8" `
  "-PmaxSampleBytes=2097152" `
  "-PevalSamples=1000"
```

建议：

- `dictSize=32768`：轻量，适合小服或低内存环境。
- `dictSize=65536`：推荐默认值。
- `dictSize=131072`：样本很多且区块/模组 payload 重复度高时再尝试。
- 样本总字节数至少应为字典大小的 8 倍，更理想是 50 到 100 倍。

训练器会输出：

- 字典 ID。
- 明文 Zstd 字典大小。
- Krypton 元数据包装后的文件大小。
- 同一批样本上 plain Zstd 和 dict Zstd 的压缩对比。

## 3. 检查字典

训练完成后可以检查文件：

```powershell
.\gradlew.bat inspectZstdDict "-PdictPath=config/krypton_hybrid.zdict"
```

训练器本身也支持 inspect 模式；如果在 IDE 中运行，参数为 `inspect config/krypton_hybrid.zdict`。

## 4. 应用字典

把同一个 `config/krypton_hybrid.zdict` 分发到：

- dedicated server 的 `config/krypton_hybrid.zdict`
- 每个客户端的 `config/krypton_hybrid.zdict`

然后服务端和客户端都配置：

```toml
[compression]
algorithm = "ZSTD"

[zstd_advanced]
dict_enabled = true
dict_path = "config/krypton_hybrid.zdict"
dict_required = true
```

首次上线建议 `dict_required = true`，这样字典缺失或损坏会直接暴露，而不是静默回退到普通 Zstd。确认所有客户端部署正确后，也可以改为 `false` 以提高容错。

运行中可重载字典：

```mcfunction
/krypton zstd dict reload
/krypton zstd status
```

注意：已有连接的 Zstd context 通常不会自动替换。字典变更后，最好重启服务端和客户端，或至少让玩家重新连接。

## 5. 推荐上线流程

1. 在测试服打开 `dict_training_capture_enabled`。
2. 采集 5 到 20 分钟真实流量。
3. 关闭采集，训练 `config/krypton_hybrid.zdict`。
4. 用测试客户端配置同一个字典并连接测试服。
5. 观察 `/krypton stats show` 和 `/krypton zstd status`。
6. 没有解压错误后，把字典和配置同步到生产服与客户端。

## 6. 回滚

如果客户端连接失败、日志出现 Zstd 解压错误，先回滚字典：

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

服务端和客户端必须保持一致。

## 7. 常见问题

`/krypton zstd status` 显示 `dict=error(...)`：
检查 `dict_path` 是否正确，文件是否分发到了服务端和客户端同一路径。

训练器显示字典收益为负：
样本不够代表真实流量，或字典过大/过小。重新采集覆盖更多区块、登录和模组同步场景，尝试 `dictSize=32768` 或 `65536`。

采样目录增长太快：
提高 `dict_training_sample_every`，降低 `dict_training_max_samples`，或降低 `dict_training_max_bytes`。

字典是否需要和配置一起发布：
需要。`dict_enabled=true` 时，字典文件本身就是连接协议的一部分。服务端和客户端必须使用同一份文件。
