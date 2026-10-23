package com.xinian.KryptonHybrid.shared.network.compression;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional packet sample recorder for Zstd dictionary training.
 *
 * <p>The recorder writes serialized, uncompressed packet bytes to disk before the
 * Zstd encoder compresses them. It is deliberately disabled by default because
 * it copies packet bytes and performs asynchronous disk writes. Use it only while
 * collecting representative samples for {@link com.xinian.KryptonHybrid.tool.ZstdDictionaryTrainer}.</p>
 *
 * <h3>What is sampled</h3>
 * <ul>
 *   <li>Only packets the encoder actually compresses (at or above the compression
 *       threshold): smaller ones are sent raw and never meet the dictionary.</li>
 *   <li>Each packet object once: a broadcast packet encoded for many players would
 *       otherwise be stored once per recipient.</li>
 *   <li>Not Krypton Hybrid's own channels (stats snapshots, config sync).</li>
 * </ul>
 *
 * <p>Writes queued for the disk are capped by size ({@link #MAX_PENDING_BYTES}), and only
 * samples actually written count towards {@code dict_training_max_samples}. Relative
 * directories are resolved against the game directory.</p>
 */
public final class ZstdSampleRecorder {
    /** Bytes of samples waiting for the disk; more are dropped until the writer catches up. */
    private static final long MAX_PENDING_BYTES = 32L * 1024 * 1024;
    private static final String OWN_NAMESPACE = "krypton_hybrid";
    private static final String RUN_ID = Long.toUnsignedString(System.currentTimeMillis(), 36);
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Krypton-Zstd-Sample-Writer");
        thread.setDaemon(true);
        return thread;
    });

    /** Packet objects already sampled (weak keys, identity comparison). */
    private static final Cache<Object, Boolean> SAMPLED = CacheBuilder.newBuilder()
            .weakKeys()
            .maximumSize(16384)
            .build();

    private static final AtomicLong eligiblePackets = new AtomicLong();
    /** Samples written plus samples queued; limits capture to the configured maximum. */
    private static final AtomicLong reservedSamples = new AtomicLong();
    private static final AtomicLong savedSamples = new AtomicLong();
    private static final AtomicLong fileSequence = new AtomicLong();
    private static final AtomicLong failedWrites = new AtomicLong();
    private static final AtomicLong pendingBytes = new AtomicLong();

    private ZstdSampleRecorder() {}

    /**
     * Considers a packet the encoder is about to compress. {@code msg} must still hold
     * the uncompressed bytes at its reader index.
     */
    public static void maybeRecord(Packet<?> packet, ByteBuf msg, int readableBytes) {
        if (!KryptonConfig.zstdDictTrainingCaptureEnabled) {
            return;
        }

        int minBytes = Math.max(1, KryptonConfig.zstdDictTrainingMinBytes);
        int maxBytes = Math.max(minBytes, KryptonConfig.zstdDictTrainingMaxBytes);
        if (readableBytes < minBytes || readableBytes > maxBytes) {
            return;
        }
        if (isOwnChannel(packet)) {
            return;
        }

        long maxSamples = Math.max(1L, KryptonConfig.zstdDictTrainingMaxSamples);
        if (reservedSamples.get() >= maxSamples) {
            return;
        }
        if (packet != null && SAMPLED.asMap().putIfAbsent(packet, Boolean.TRUE) != null) {
            return;
        }

        long eligible = eligiblePackets.incrementAndGet();
        int sampleEvery = Math.max(1, KryptonConfig.zstdDictTrainingSampleEvery);
        if ((eligible - 1L) % sampleEvery != 0L) {
            return;
        }

        if (pendingBytes.addAndGet(readableBytes) > MAX_PENDING_BYTES) {
            pendingBytes.addAndGet(-readableBytes);
            return;
        }
        if (!reserveSample(maxSamples)) {
            pendingBytes.addAndGet(-readableBytes);
            return;
        }

        byte[] copy = new byte[readableBytes];
        msg.getBytes(msg.readerIndex(), copy);

        Path outputDir = resolveSamplesDir();
        String packetName = packetName(packet);
        long fileNo = fileSequence.incrementAndGet();
        WRITER.execute(() -> writeSample(outputDir, packetName, fileNo, copy));
    }

    public static String statusDescription() {
        if (!KryptonConfig.zstdDictTrainingCaptureEnabled) {
            return "off";
        }
        return String.format(Locale.ROOT,
                "on(dir=%s, saved=%d/%d, seen=%d, pending=%dKB, failed=%d, every=%d, bytes=%d..%d)",
                resolveSamplesDir(),
                savedSamples.get(),
                Math.max(1, KryptonConfig.zstdDictTrainingMaxSamples),
                eligiblePackets.get(),
                pendingBytes.get() / 1024,
                failedWrites.get(),
                Math.max(1, KryptonConfig.zstdDictTrainingSampleEvery),
                Math.max(1, KryptonConfig.zstdDictTrainingMinBytes),
                Math.max(Math.max(1, KryptonConfig.zstdDictTrainingMinBytes), KryptonConfig.zstdDictTrainingMaxBytes));
    }

    private static boolean isOwnChannel(Packet<?> packet) {
        ResourceLocation id = null;
        if (packet instanceof ClientboundCustomPayloadPacket cp) {
            id = cp.payload().type().id();
        } else if (packet instanceof ServerboundCustomPayloadPacket sp) {
            id = sp.payload().type().id();
        }
        return id != null && OWN_NAMESPACE.equals(id.getNamespace());
    }

    private static boolean reserveSample(long maxSamples) {
        while (true) {
            long current = reservedSamples.get();
            if (current >= maxSamples) {
                return false;
            }
            if (reservedSamples.compareAndSet(current, current + 1L)) {
                return true;
            }
        }
    }

    private static void writeSample(Path outputDir, String packetName, long fileNo, byte[] bytes) {
        try {
            Files.createDirectories(outputDir);
            String fileName = String.format(Locale.ROOT,
                    "%s_%08d_%s_%d.pkt",
                    RUN_ID,
                    fileNo,
                    packetName,
                    bytes.length);
            Files.write(outputDir.resolve(fileName), bytes);
            long saved = savedSamples.incrementAndGet();
            if (saved == 1L || saved % 1000L == 0L) {
                KryptonSharedBootstrap.LOGGER.info(
                        "Zstd dictionary sample capture saved {}/{} samples to {}",
                        saved,
                        Math.max(1, KryptonConfig.zstdDictTrainingMaxSamples),
                        outputDir);
            }
        } catch (IOException ioe) {
            // Give the slot back: only written samples count towards the maximum.
            reservedSamples.decrementAndGet();
            long failures = failedWrites.incrementAndGet();
            if (failures == 1L || failures % 100L == 0L) {
                KryptonSharedBootstrap.LOGGER.warn(
                        "Failed writing Zstd dictionary training sample to {}: {}",
                        outputDir,
                        ioe.toString());
            }
        } finally {
            pendingBytes.addAndGet(-bytes.length);
        }
    }

    /** The configured sample directory; relative paths start at the game directory. */
    private static Path resolveSamplesDir() {
        String configuredPath = KryptonConfig.zstdDictTrainingSamplesDir == null
                ? ""
                : KryptonConfig.zstdDictTrainingSamplesDir.trim();
        if (configuredPath.isEmpty()) {
            configuredPath = "krypton_zstd_samples";
        }
        Path path = Paths.get(configuredPath);
        return path.isAbsolute() ? path.normalize() : FMLPaths.GAMEDIR.get().resolve(path).normalize();
    }

    private static String packetName(Packet<?> packet) {
        if (packet instanceof ClientboundCustomPayloadPacket cp) {
            return sanitizePayloadId(cp.payload().type().id());
        }
        if (packet instanceof ServerboundCustomPayloadPacket sp) {
            return sanitizePayloadId(sp.payload().type().id());
        }
        if (packet == null) {
            return "unknown";
        }
        return sanitize(packet.getClass().getSimpleName());
    }

    private static String sanitizePayloadId(ResourceLocation id) {
        return sanitize("custom_" + id.getNamespace() + "_" + id.getPath());
    }

    private static String sanitize(String input) {
        String safe = input.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.isEmpty()) {
            return "packet";
        }
        return safe.length() <= 80 ? safe : safe.substring(0, 80);
    }
}
