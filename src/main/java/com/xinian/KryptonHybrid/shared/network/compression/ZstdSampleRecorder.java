package com.xinian.KryptonHybrid.shared.network.compression;

import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.KryptonSharedBootstrap;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional packet sample recorder for Zstd dictionary training.
 *
 * <p>The recorder writes serialized, uncompressed packet bytes to disk before the
 * Zstd encoder mutates the buffer. It is deliberately disabled by default because
 * it copies packet bytes and performs asynchronous disk writes. Use it only while
 * collecting representative samples for {@link com.xinian.KryptonHybrid.tool.ZstdDictionaryTrainer}.</p>
 */
public final class ZstdSampleRecorder {
    private static final int MAX_PENDING_WRITES = 2048;
    private static final String RUN_ID = Long.toUnsignedString(System.currentTimeMillis(), 36);
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Krypton-Zstd-Sample-Writer");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicLong eligiblePackets = new AtomicLong();
    private static final AtomicLong savedSamples = new AtomicLong();
    private static final AtomicLong failedWrites = new AtomicLong();
    private static final AtomicInteger pendingWrites = new AtomicInteger();

    private ZstdSampleRecorder() {}

    public static void maybeRecord(Packet<?> packet, ByteBuf msg, int readableBytes) {
        if (!KryptonConfig.zstdDictTrainingCaptureEnabled) {
            return;
        }

        int minBytes = Math.max(1, KryptonConfig.zstdDictTrainingMinBytes);
        int maxBytes = Math.max(minBytes, KryptonConfig.zstdDictTrainingMaxBytes);
        if (readableBytes < minBytes || readableBytes > maxBytes) {
            return;
        }

        long maxSamples = Math.max(1L, KryptonConfig.zstdDictTrainingMaxSamples);
        if (savedSamples.get() >= maxSamples) {
            return;
        }

        long eligible = eligiblePackets.incrementAndGet();
        int sampleEvery = Math.max(1, KryptonConfig.zstdDictTrainingSampleEvery);
        if ((eligible - 1L) % sampleEvery != 0L) {
            return;
        }

        if (pendingWrites.incrementAndGet() > MAX_PENDING_WRITES) {
            pendingWrites.decrementAndGet();
            return;
        }

        long sampleNo = reserveSample(maxSamples);
        if (sampleNo < 0L) {
            pendingWrites.decrementAndGet();
            return;
        }

        byte[] copy = new byte[readableBytes];
        msg.getBytes(msg.readerIndex(), copy);

        Path outputDir = resolveSamplesDir();
        String packetName = packetName(packet);
        WRITER.execute(() -> writeSample(outputDir, packetName, sampleNo, copy));
    }

    public static String statusDescription() {
        if (!KryptonConfig.zstdDictTrainingCaptureEnabled) {
            return "off";
        }
        return String.format(Locale.ROOT,
                "on(dir=%s, saved=%d/%d, seen=%d, pending=%d, failed=%d, every=%d, bytes=%d..%d)",
                resolveSamplesDir(),
                savedSamples.get(),
                Math.max(1, KryptonConfig.zstdDictTrainingMaxSamples),
                eligiblePackets.get(),
                pendingWrites.get(),
                failedWrites.get(),
                Math.max(1, KryptonConfig.zstdDictTrainingSampleEvery),
                Math.max(1, KryptonConfig.zstdDictTrainingMinBytes),
                Math.max(Math.max(1, KryptonConfig.zstdDictTrainingMinBytes), KryptonConfig.zstdDictTrainingMaxBytes));
    }

    private static long reserveSample(long maxSamples) {
        while (true) {
            long current = savedSamples.get();
            if (current >= maxSamples) {
                return -1L;
            }
            long next = current + 1L;
            if (savedSamples.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    private static void writeSample(Path outputDir, String packetName, long sampleNo, byte[] bytes) {
        try {
            Files.createDirectories(outputDir);
            String fileName = String.format(Locale.ROOT,
                    "%s_%08d_%s_%d.pkt",
                    RUN_ID,
                    sampleNo,
                    packetName,
                    bytes.length);
            Files.write(outputDir.resolve(fileName), bytes);
            if (sampleNo == 1L || sampleNo % 1000L == 0L) {
                KryptonSharedBootstrap.LOGGER.info(
                        "Zstd dictionary sample capture saved {}/{} samples to {}",
                        sampleNo,
                        Math.max(1, KryptonConfig.zstdDictTrainingMaxSamples),
                        outputDir);
            }
        } catch (IOException ioe) {
            long failures = failedWrites.incrementAndGet();
            if (failures == 1L || failures % 100L == 0L) {
                KryptonSharedBootstrap.LOGGER.warn(
                        "Failed writing Zstd dictionary training sample to {}: {}",
                        outputDir,
                        ioe.toString());
            }
        } finally {
            pendingWrites.decrementAndGet();
        }
    }

    private static Path resolveSamplesDir() {
        String configuredPath = KryptonConfig.zstdDictTrainingSamplesDir == null
                ? ""
                : KryptonConfig.zstdDictTrainingSamplesDir.trim();
        if (configuredPath.isEmpty()) {
            configuredPath = "run/krypton_zstd_samples";
        }
        Path path = Paths.get(configuredPath);
        return path.isAbsolute() ? path.normalize() : Paths.get("").resolve(path).normalize();
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
