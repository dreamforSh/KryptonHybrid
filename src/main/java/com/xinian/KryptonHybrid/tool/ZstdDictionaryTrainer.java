package com.xinian.KryptonHybrid.tool;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdDictionaryMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

/**
 * CLI utility to train and inspect Krypton Hybrid Zstd dictionaries.
 *
 * <p>Legacy positional usage is still supported:
 * <pre>
 *   java com.xinian.KryptonHybrid.tool.ZstdDictionaryTrainer samplesDir outputDict [dictSize] [maxSamples]
 * </pre>
 */
public final class ZstdDictionaryTrainer {

    private static final int DEFAULT_DICT_SIZE = 64 * 1024;
    private static final int DEFAULT_MAX_SAMPLES = 8000;
    private static final int DEFAULT_MIN_SAMPLE_BYTES = 8;
    private static final int DEFAULT_MAX_SAMPLE_BYTES = 2 * 1024 * 1024;
    private static final int DEFAULT_EVAL_SAMPLES = 1000;
    private static final int MIN_SAMPLES = 8;
    private static final long SHUFFLE_SEED = 0x4B485A4453544431L; // KHZDSTD1

    private ZstdDictionaryTrainer() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsageAndExit();
            return;
        }

        if ("inspect".equalsIgnoreCase(args[0]) || "--inspect".equalsIgnoreCase(args[0])) {
            if (args.length != 2) {
                printUsageAndExit();
                return;
            }
            inspectDictionary(Paths.get(args[1]).toAbsolutePath().normalize());
            return;
        }

        TrainOptions options = parseTrainOptions(args);
        train(options);
    }

    private static void train(TrainOptions options) throws Exception {
        if (!Files.isDirectory(options.samplesDir())) {
            throw new IllegalArgumentException("Sample directory not found: " + options.samplesDir());
        }
        if (options.dictSize() < 1024) {
            throw new IllegalArgumentException("Dictionary size too small: " + options.dictSize());
        }
        if (options.maxSamples() < MIN_SAMPLES) {
            throw new IllegalArgumentException("maxSamples too small: " + options.maxSamples());
        }
        if (options.minSampleBytes() < 1 || options.maxSampleBytes() < options.minSampleBytes()) {
            throw new IllegalArgumentException("Invalid sample byte range: "
                    + options.minSampleBytes() + ".." + options.maxSampleBytes());
        }

        System.out.println("Loading samples from: " + options.samplesDir());
        System.out.println("Max samples: " + options.maxSamples()
                + ", target dict size: " + options.dictSize() + " bytes"
                + ", sample bytes: " + options.minSampleBytes() + ".." + options.maxSampleBytes());

        SampleSet sampleSet = loadSamples(options);
        List<byte[]> samples = sampleSet.samples();
        if (samples.size() < MIN_SAMPLES) {
            throw new IllegalStateException("Need at least " + MIN_SAMPLES
                    + " eligible non-empty samples, found " + samples.size());
        }

        if (sampleSet.totalBytes() < options.dictSize() * 8L) {
            System.out.println("Warning: sample corpus is small for this dictionary size. "
                    + "Prefer at least 8x-100x more sample bytes than dictionary bytes.");
        }

        System.out.println("Training dictionary from " + samples.size()
                + " samples (" + formatBytes(sampleSet.totalBytes()) + ")...");

        byte[] dictBuffer = new byte[options.dictSize()];
        long result = Zstd.trainFromBuffer(samples.toArray(new byte[0][]), dictBuffer);
        if (Zstd.isError(result)) {
            throw new IllegalStateException("Dictionary training failed: " + Zstd.getErrorName(result));
        }

        int producedSize = (int) result;
        byte[] plainDict = new byte[producedSize];
        System.arraycopy(dictBuffer, 0, plainDict, 0, producedSize);

        long dictId = Zstd.getDictIdFromDict(plainDict);
        validateDictionary(plainDict);

        byte[] wrappedDict = ZstdDictionaryMetadata.wrap(plainDict, dictId, samples.size());

        Path parent = options.outputPath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(options.outputPath(), wrappedDict);

        System.out.println();
        System.out.println("=== Dictionary Training Complete ===");
        System.out.println("Output file:       " + options.outputPath());
        System.out.println("Samples used:      " + samples.size());
        System.out.println("Plain dict size:   " + producedSize + " bytes");
        System.out.println("Total file size:   " + wrappedDict.length + " bytes");
        System.out.println("Dictionary ID:     " + dictId);
        System.out.println("Metadata header:   " + (wrappedDict.length - producedSize) + " bytes");
        System.out.println("Skipped small:     " + sampleSet.skippedSmall());
        System.out.println("Skipped large:     " + sampleSet.skippedLarge());
        System.out.println();

        displayCompressionStats(samples, plainDict, options.evalSamples());
    }

    private static SampleSet loadSamples(TrainOptions options) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(options.samplesDir())) {
            files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        System.out.println("Found " + files.size() + " files in directory");

        List<Path> shuffled = new ArrayList<>(files);
        Collections.shuffle(shuffled, new Random(SHUFFLE_SEED));

        List<byte[]> result = new ArrayList<>(Math.min(options.maxSamples(), shuffled.size()));
        long totalBytes = 0L;
        int skippedSmall = 0;
        int skippedLarge = 0;

        for (Path path : shuffled) {
            if (result.size() >= options.maxSamples()) {
                break;
            }

            long size = Files.size(path);
            if (size < options.minSampleBytes()) {
                skippedSmall++;
                continue;
            }
            if (size > options.maxSampleBytes()) {
                skippedLarge++;
                continue;
            }

            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > 0) {
                result.add(bytes);
                totalBytes += bytes.length;
            }
        }

        System.out.println("Loaded " + result.size() + " samples (" + formatBytes(totalBytes) + " total)");
        return new SampleSet(result, totalBytes, skippedSmall, skippedLarge);
    }

    private static void displayCompressionStats(List<byte[]> samples, byte[] dict, int evalLimit) {
        int evalCount = Math.min(Math.max(1, evalLimit), samples.size());
        long originalTotal = 0L;
        long plainZstdTotal = 0L;
        long dictZstdTotal = 0L;
        int dictWins = 0;

        ZstdCompressCtx plainCtx = null;
        ZstdCompressCtx dictCtx = null;
        try {
            plainCtx = new ZstdCompressCtx();
            plainCtx.setLevel(3);
            plainCtx.setChecksum(false);
            plainCtx.setContentSize(false);

            dictCtx = new ZstdCompressCtx();
            dictCtx.setLevel(3);
            dictCtx.setChecksum(false);
            dictCtx.setContentSize(false);
            dictCtx.loadDict(dict);

            for (int i = 0; i < evalCount; i++) {
                byte[] sample = samples.get(i);
                long plainSize = compressSize(plainCtx, sample);
                long dictSize = compressSize(dictCtx, sample);
                if (plainSize < 0L || dictSize < 0L) {
                    continue;
                }
                originalTotal += sample.length;
                plainZstdTotal += plainSize;
                dictZstdTotal += dictSize;
                if (dictSize < plainSize) {
                    dictWins++;
                }
            }
        } catch (Exception e) {
            System.out.println("Note: Could not compute compression stats: " + e.getMessage());
            return;
        } finally {
            if (plainCtx != null) {
                plainCtx.close();
            }
            if (dictCtx != null) {
                dictCtx.close();
            }
        }

        if (originalTotal <= 0L || plainZstdTotal <= 0L || dictZstdTotal <= 0L) {
            System.out.println("Note: No valid samples were available for compression stats.");
            return;
        }

        double plainRatio = plainZstdTotal * 100.0 / originalTotal;
        double dictRatio = dictZstdTotal * 100.0 / originalTotal;
        double gainVsPlain = (plainZstdTotal - dictZstdTotal) * 100.0 / plainZstdTotal;

        System.out.println("=== Compression Sanity Check ===");
        System.out.println("Evaluated samples: " + evalCount + " (level 3, same corpus)");
        System.out.println("Original total:    " + formatBytes(originalTotal));
        System.out.println("Plain Zstd total:  " + formatBytes(plainZstdTotal)
                + " (" + String.format(Locale.ROOT, "%.2f%%", plainRatio) + ")");
        System.out.println("Dict Zstd total:   " + formatBytes(dictZstdTotal)
                + " (" + String.format(Locale.ROOT, "%.2f%%", dictRatio) + ")");
        System.out.println("Dictionary gain:   " + String.format(Locale.ROOT, "%.2f%%", gainVsPlain)
                + " vs plain Zstd");
        System.out.println("Per-sample wins:   " + dictWins + " / " + evalCount);
    }

    private static long compressSize(ZstdCompressCtx ctx, byte[] sample) {
        byte[] out = new byte[(int) Zstd.compressBound(sample.length)];
        long size = ctx.compress(out, sample);
        return Zstd.isError(size) ? -1L : size;
    }

    private static void inspectDictionary(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Dictionary file not found: " + path);
        }

        byte[] bytes = Files.readAllBytes(path);
        ZstdDictionaryMetadata metadata = ZstdDictionaryMetadata.tryParse(bytes);
        byte[] plain = metadata != null ? metadata.getPlainDictionary() : bytes;
        validateDictionary(plain);

        long dictId = metadata != null ? metadata.getDictID() : Zstd.getDictIdFromDict(plain);
        System.out.println("=== Krypton Zstd Dictionary ===");
        System.out.println("File:          " + path);
        System.out.println("Format:        " + (metadata != null ? "Krypton wrapped" : "Plain Zstd dictionary"));
        System.out.println("Total size:    " + bytes.length + " bytes");
        System.out.println("Plain size:    " + plain.length + " bytes");
        System.out.println("Dictionary ID: " + dictId);
        if (metadata != null) {
            System.out.println("Samples:       " + metadata.getSampleCount());
            System.out.println("Created:       " + DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(metadata.getCreationTime())));
            System.out.println("SHA-256:       " + toHex(metadata.getHash(), metadata.getHash().length));
        }
        System.out.println("Validation:    loadable by zstd-jni");
    }

    private static void validateDictionary(byte[] plainDictionary) {
        ZstdCompressCtx compressCtx = null;
        ZstdDecompressCtx decompressCtx = null;
        try {
            compressCtx = new ZstdCompressCtx();
            compressCtx.loadDict(plainDictionary);
            decompressCtx = new ZstdDecompressCtx();
            decompressCtx.loadDict(plainDictionary);
        } finally {
            if (compressCtx != null) {
                compressCtx.close();
            }
            if (decompressCtx != null) {
                decompressCtx.close();
            }
        }
    }

    private static TrainOptions parseTrainOptions(String[] args) {
        if (args[0].startsWith("--")) {
            return parseNamedTrainOptions(args);
        }
        if (args.length < 2 || args.length > 7) {
            printUsageAndExit();
            throw new IllegalArgumentException("Invalid argument count: " + args.length);
        }
        return new TrainOptions(
                Paths.get(args[0]).toAbsolutePath().normalize(),
                Paths.get(args[1]).toAbsolutePath().normalize(),
                args.length >= 3 ? Integer.parseInt(args[2]) : DEFAULT_DICT_SIZE,
                args.length >= 4 ? Integer.parseInt(args[3]) : DEFAULT_MAX_SAMPLES,
                args.length >= 5 ? Integer.parseInt(args[4]) : DEFAULT_MIN_SAMPLE_BYTES,
                args.length >= 6 ? Integer.parseInt(args[5]) : DEFAULT_MAX_SAMPLE_BYTES,
                args.length >= 7 ? Integer.parseInt(args[6]) : DEFAULT_EVAL_SAMPLES
        );
    }

    private static TrainOptions parseNamedTrainOptions(String[] args) {
        Path samplesDir = null;
        Path outputPath = null;
        int dictSize = DEFAULT_DICT_SIZE;
        int maxSamples = DEFAULT_MAX_SAMPLES;
        int minSampleBytes = DEFAULT_MIN_SAMPLE_BYTES;
        int maxSampleBytes = DEFAULT_MAX_SAMPLE_BYTES;
        int evalSamples = DEFAULT_EVAL_SAMPLES;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String value = i + 1 < args.length ? args[i + 1] : null;
            switch (arg) {
                case "--samples" -> {
                    samplesDir = requirePath(arg, value);
                    i++;
                }
                case "--output" -> {
                    outputPath = requirePath(arg, value);
                    i++;
                }
                case "--dict-size" -> {
                    dictSize = requireInt(arg, value);
                    i++;
                }
                case "--max-samples" -> {
                    maxSamples = requireInt(arg, value);
                    i++;
                }
                case "--min-sample-bytes" -> {
                    minSampleBytes = requireInt(arg, value);
                    i++;
                }
                case "--max-sample-bytes" -> {
                    maxSampleBytes = requireInt(arg, value);
                    i++;
                }
                case "--eval-samples" -> {
                    evalSamples = requireInt(arg, value);
                    i++;
                }
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }

        if (samplesDir == null || outputPath == null) {
            printUsageAndExit();
            throw new IllegalArgumentException("Missing --samples or --output");
        }
        return new TrainOptions(samplesDir, outputPath, dictSize, maxSamples, minSampleBytes, maxSampleBytes, evalSamples);
    }

    private static Path requirePath(String option, String value) {
        if (value == null || value.startsWith("--")) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return Paths.get(value).toAbsolutePath().normalize();
    }

    private static int requireInt(String option, String value) {
        if (value == null || value.startsWith("--")) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return Integer.parseInt(value);
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0L) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = Math.min(units.length - 1, (int) (Math.log10(bytes) / Math.log10(1024)));
        return String.format(Locale.ROOT, "%.2f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private static String toHex(byte[] bytes, int maxBytes) {
        StringBuilder sb = new StringBuilder(Math.min(bytes.length, maxBytes) * 2);
        int count = Math.min(bytes.length, maxBytes);
        for (int i = 0; i < count; i++) {
            sb.append(Character.forDigit((bytes[i] >>> 4) & 0x0F, 16));
            sb.append(Character.forDigit(bytes[i] & 0x0F, 16));
        }
        return sb.toString();
    }

    private static void printUsageAndExit() {
        System.out.println("Usage:");
        System.out.println("  java com.xinian.KryptonHybrid.tool.ZstdDictionaryTrainer <samplesDir> <outputDictPath> [dictSize] [maxSamples] [minSampleBytes] [maxSampleBytes] [evalSamples]");
        System.out.println("  java com.xinian.KryptonHybrid.tool.ZstdDictionaryTrainer --samples <dir> --output <path> [--dict-size n] [--max-samples n] [--min-sample-bytes n] [--max-sample-bytes n] [--eval-samples n]");
        System.out.println("  java com.xinian.KryptonHybrid.tool.ZstdDictionaryTrainer inspect <dictPath>");
        System.out.println();
        System.out.println("Defaults:");
        System.out.println("  dictSize=65536, maxSamples=8000, minSampleBytes=8, maxSampleBytes=2097152, evalSamples=1000");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  ./gradlew trainZstdDict -PsamplesDir=run/krypton_zstd_samples -PoutputDict=config/krypton_hybrid.zdict -PdictSize=65536 -PmaxSamples=8000");
    }

    private record TrainOptions(
            Path samplesDir,
            Path outputPath,
            int dictSize,
            int maxSamples,
            int minSampleBytes,
            int maxSampleBytes,
            int evalSamples
    ) {}

    private record SampleSet(
            List<byte[]> samples,
            long totalBytes,
            int skippedSmall,
            int skippedLarge
    ) {}
}
