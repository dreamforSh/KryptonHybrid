package com.xinian.KryptonHybrid.tool;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import com.xinian.KryptonHybrid.shared.network.compression.ZstdDictionaryMetadata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * CLI utility to train and inspect Krypton Hybrid Zstd dictionaries.
 *
 * <h3>Training set</h3>
 * <p>A dictionary only pays off on packets that are compressed with it: at or above the
 * compression threshold ({@code --min-sample-bytes}, default 256) and at most
 * {@code dict_max_packet_bytes} ({@code --max-sample-bytes}, default 16384). Those are
 * the core samples. Larger samples (chunk data) would otherwise dominate the corpus by
 * size; a capped share of them ({@code --large-sample-share}) is still added because their
 * structures also recur in medium packets. Identical samples are used once.</p>
 *
 * <h3>Evaluation</h3>
 * <p>A stable share of the files ({@code --eval-share}, by file name) is held out and never
 * trained on. The report compresses those held-out core-range samples the way the
 * encoder sends them: a VarInt size header plus a magicless dictionary frame, or a
 * standard frame without the dictionary, and raw bytes whenever that is smaller.</p>
 *
 * <p>Legacy positional usage is still supported:
 * <pre>
 *   java com.xinian.KryptonHybrid.tool.ZstdDictionaryTrainer samplesDir outputDict [dictSize] [maxSamples]
 *        [minSampleBytes] [maxSampleBytes] [evalSamples]
 * </pre>
 */
public final class ZstdDictionaryTrainer {

    private static final int DEFAULT_DICT_SIZE = 64 * 1024;
    private static final int DEFAULT_MAX_SAMPLES = 8000;
    private static final int DEFAULT_MIN_SAMPLE_BYTES = 256;
    private static final int DEFAULT_MAX_SAMPLE_BYTES = 16 * 1024;
    private static final double DEFAULT_LARGE_SAMPLE_SHARE = 0.25;
    private static final double DEFAULT_EVAL_SHARE = 0.2;
    private static final int DEFAULT_EVAL_SAMPLES = 1000;
    /** Samples above this are never read (the recorder caps at 2 MiB as well). */
    private static final int LARGE_SAMPLE_LIMIT = 2 * 1024 * 1024;
    private static final int MIN_SAMPLES = 8;
    private static final int[] EVAL_LEVELS = {3, 6};
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

    // ------------------------------------------------------------------
    //  Training
    // ------------------------------------------------------------------

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
        if (options.largeSampleShare() < 0 || options.evalShare() < 0 || options.evalShare() >= 1) {
            throw new IllegalArgumentException("Invalid share: large=" + options.largeSampleShare()
                    + ", eval=" + options.evalShare());
        }

        System.out.println("Loading samples from: " + options.samplesDir());
        System.out.println("Core sample bytes: " + options.minSampleBytes() + ".." + options.maxSampleBytes()
                + ", max core samples: " + options.maxSamples()
                + ", large share: " + options.largeSampleShare()
                + ", held out: " + Math.round(options.evalShare() * 100) + "%"
                + ", target dict size: " + options.dictSize() + " bytes");

        Corpus corpus = loadCorpus(options);
        List<byte[]> trainSet = new ArrayList<>(corpus.core());
        trainSet.addAll(corpus.large());
        if (corpus.core().size() < MIN_SAMPLES) {
            throw new IllegalStateException("Need at least " + MIN_SAMPLES
                    + " core samples (" + options.minSampleBytes() + ".." + options.maxSampleBytes()
                    + " bytes), found " + corpus.core().size()
                    + ". Capture more traffic, or check that capture ran with Zstd compression on.");
        }

        long trainBytes = totalBytes(trainSet);
        if (trainBytes < options.dictSize() * 8L) {
            System.out.println("Warning: sample corpus is small for this dictionary size. "
                    + "Prefer at least 8x-100x more sample bytes than dictionary bytes.");
        }

        System.out.println("Training dictionary from " + trainSet.size()
                + " samples (" + formatBytes(trainBytes) + ")...");

        byte[] dictBuffer = new byte[options.dictSize()];
        long result = Zstd.trainFromBuffer(trainSet.toArray(new byte[0][]), dictBuffer);
        if (Zstd.isError(result)) {
            throw new IllegalStateException("Dictionary training failed: " + Zstd.getErrorName(result));
        }

        int producedSize = (int) result;
        byte[] plainDict = new byte[producedSize];
        System.arraycopy(dictBuffer, 0, plainDict, 0, producedSize);

        long dictId = Zstd.getDictIdFromDict(plainDict);
        if (dictId == 0L) {
            throw new IllegalStateException("Trained dictionary has no ID; Krypton cannot negotiate it");
        }
        validateDictionary(plainDict);

        byte[] wrappedDict = ZstdDictionaryMetadata.wrap(plainDict, dictId, trainSet.size());

        Path parent = options.outputPath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(options.outputPath(), wrappedDict);

        System.out.println();
        System.out.println("=== Dictionary Training Complete ===");
        System.out.println("Output file:       " + options.outputPath());
        System.out.println("Core samples:      " + corpus.core().size() + " (" + formatBytes(totalBytes(corpus.core())) + ")");
        System.out.println("Large samples:     " + corpus.large().size() + " (" + formatBytes(totalBytes(corpus.large())) + ")");
        System.out.println("Plain dict size:   " + producedSize + " bytes");
        System.out.println("Total file size:   " + wrappedDict.length + " bytes");
        System.out.println("Dictionary ID:     " + dictId);
        System.out.println("Skipped small:     " + corpus.skippedSmall() + " (below " + options.minSampleBytes()
                + " bytes, never compressed)");
        System.out.println("Skipped too large: " + corpus.skippedHuge());
        System.out.println("Duplicates:        " + corpus.duplicates());
        System.out.println("Held out:          " + corpus.heldOut() + " files");
        System.out.println();

        evaluate(corpus.eval(), plainDict, options);
    }

    private record Candidate(Path path, long size) {}

    private record Corpus(List<byte[]> core, List<byte[]> large, List<byte[]> eval,
                          int skippedSmall, int skippedHuge, int duplicates, int heldOut) {}

    private static Corpus loadCorpus(TrainOptions options) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(options.samplesDir())) {
            files = stream.filter(Files::isRegularFile).sorted().toList();
        }
        System.out.println("Found " + files.size() + " files in directory");

        List<Candidate> coreCandidates = new ArrayList<>();
        List<Candidate> largeCandidates = new ArrayList<>();
        List<Candidate> evalCandidates = new ArrayList<>();
        int skippedSmall = 0;
        int skippedHuge = 0;
        int heldOut = 0;
        for (Path path : files) {
            long size = Files.size(path);
            if (size < options.minSampleBytes()) {
                skippedSmall++;
                continue;
            }
            if (size > LARGE_SAMPLE_LIMIT) {
                skippedHuge++;
                continue;
            }
            Candidate candidate = new Candidate(path, size);
            if (heldOut(path, options.evalShare())) {
                heldOut++;
                // Only the range that is compressed with the dictionary is evaluated.
                if (size <= options.maxSampleBytes()) {
                    evalCandidates.add(candidate);
                }
            } else if (size <= options.maxSampleBytes()) {
                coreCandidates.add(candidate);
            } else {
                largeCandidates.add(candidate);
            }
        }

        Random random = new Random(SHUFFLE_SEED);
        Collections.shuffle(coreCandidates, random);
        Collections.shuffle(largeCandidates, random);
        Collections.shuffle(evalCandidates, random);

        Set<ByteBuffer> seen = new HashSet<>();
        int[] duplicates = {0};
        List<byte[]> core = read(coreCandidates, options.maxSamples(), seen, duplicates);
        int largeLimit = (int) Math.round(core.size() * options.largeSampleShare());
        List<byte[]> large = read(largeCandidates, largeLimit, seen, duplicates);
        // Held-out samples identical to a training sample would overstate the result.
        List<byte[]> eval = read(evalCandidates, options.evalSamples(), seen, new int[1]);

        System.out.println("Loaded " + core.size() + " core + " + large.size() + " large samples, "
                + eval.size() + " held-out samples for evaluation");
        return new Corpus(core, large, eval, skippedSmall, skippedHuge, duplicates[0], heldOut);
    }

    /** Reads up to {@code limit} candidates, skipping content already in {@code seen}. */
    private static List<byte[]> read(List<Candidate> candidates, int limit, Set<ByteBuffer> seen,
                                     int[] duplicates) throws IOException {
        List<byte[]> result = new ArrayList<>(Math.min(limit, candidates.size()));
        for (Candidate candidate : candidates) {
            if (result.size() >= limit) {
                break;
            }
            byte[] bytes = Files.readAllBytes(candidate.path());
            if (bytes.length == 0) {
                continue;
            }
            if (!seen.add(ByteBuffer.wrap(sha256(bytes)))) {
                duplicates[0]++;
                continue;
            }
            result.add(bytes);
        }
        return result;
    }

    /** Stable split by file name, so retraining on the same directory holds out the same files. */
    private static boolean heldOut(Path path, double share) {
        if (share <= 0) {
            return false;
        }
        int bucket = Math.floorMod(path.getFileName().toString().hashCode(), 1000);
        return bucket < share * 1000;
    }

    // ------------------------------------------------------------------
    //  Evaluation
    // ------------------------------------------------------------------

    private static final long[] BUCKET_UPPER = {1024, 4096, Long.MAX_VALUE};

    private static void evaluate(List<byte[]> eval, byte[] dict, TrainOptions options) {
        System.out.println("=== Held-out Evaluation (" + eval.size() + " samples, "
                + options.minSampleBytes() + ".." + options.maxSampleBytes() + " bytes, as sent) ===");
        if (eval.isEmpty()) {
            System.out.println("No held-out samples in range; raise --eval-share or capture more traffic.");
            return;
        }
        String[] bucketNames = {
                options.minSampleBytes() + "B-1KB",
                "1KB-4KB",
                "4KB-" + formatBytes(options.maxSampleBytes())
        };

        double worstGain = Double.MAX_VALUE;
        for (int level : EVAL_LEVELS) {
            long[] raw = new long[3];
            long[] plain = new long[3];
            long[] withDict = new long[3];
            int[] count = new int[3];
            // The prepared dictionary is closed last: the context references it.
            try (ZstdDictCompress prepared = new ZstdDictCompress(dict, level);
                 ZstdCompressCtx plainCtx = productionContext(level, false);
                 ZstdCompressCtx dictCtx = productionContext(level, true)) {
                dictCtx.loadDict(prepared);
                for (byte[] sample : eval) {
                    int b = bucket(sample.length);
                    count[b]++;
                    raw[b] += sample.length;
                    plain[b] += wireSize(plainCtx, sample);
                    withDict[b] += wireSize(dictCtx, sample);
                }
            }

            long rawTotal = 0;
            long plainTotal = 0;
            long dictTotal = 0;
            System.out.println("Level " + level + ":");
            for (int b = 0; b < 3; b++) {
                rawTotal += raw[b];
                plainTotal += plain[b];
                dictTotal += withDict[b];
                if (count[b] > 0) {
                    System.out.printf(Locale.ROOT, "  %-12s %5d samples  %10s -> %10s plain, %10s dict (%+.1f%%)%n",
                            bucketNames[b], count[b], formatBytes(raw[b]), formatBytes(plain[b]),
                            formatBytes(withDict[b]), change(withDict[b], plain[b]));
                }
            }
            double gain = -change(dictTotal, plainTotal);
            worstGain = Math.min(worstGain, gain);
            System.out.printf(Locale.ROOT, "  %-12s %5d samples  %10s -> %10s plain, %10s dict (%+.1f%%)%n",
                    "total", eval.size(), formatBytes(rawTotal), formatBytes(plainTotal),
                    formatBytes(dictTotal), change(dictTotal, plainTotal));
        }

        System.out.println();
        if (worstGain < 1.0) {
            System.out.println("Warning: the dictionary saves less than 1% on held-out packets. Capture traffic"
                    + " that covers more of the server's activity, or try a smaller --dict-size.");
        } else {
            System.out.printf(Locale.ROOT, "Dictionary saves %.1f%%+ on held-out packets it applies to.%n", worstGain);
        }
        System.out.println("Packets above " + options.maxSampleBytes() + " bytes are compressed without the"
                + " dictionary (dict_max_packet_bytes) and are not affected.");
    }

    private static int bucket(int size) {
        for (int b = 0; b < BUCKET_UPPER.length; b++) {
            if (size < BUCKET_UPPER[b]) {
                return b;
            }
        }
        return BUCKET_UPPER.length - 1;
    }

    /** Percent change of {@code value} relative to {@code base}. */
    private static double change(long value, long base) {
        return base == 0 ? 0.0 : (value - base) * 100.0 / base;
    }

    /** A context configured like the encoder's: dictionary frames are magicless. */
    private static ZstdCompressCtx productionContext(int level, boolean dictionaryFrames) {
        ZstdCompressCtx ctx = new ZstdCompressCtx();
        ctx.setLevel(level);
        ctx.setChecksum(false);
        ctx.setContentSize(false);
        ctx.setDictID(false);
        ctx.setMagicless(dictionaryFrames);
        return ctx;
    }

    /** VarInt size header plus frame, or VarInt(0) plus the raw bytes when that is smaller. */
    private static long wireSize(ZstdCompressCtx ctx, byte[] sample) {
        byte[] out = new byte[(int) Zstd.compressBound(sample.length)];
        long compressed = ctx.compress(out, sample);
        return Math.min(varIntSize(sample.length) + compressed, 1L + sample.length);
    }

    private static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    // ------------------------------------------------------------------
    //  Inspect
    // ------------------------------------------------------------------

    private static void inspectDictionary(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Dictionary file not found: " + path);
        }

        byte[] bytes = Files.readAllBytes(path);
        ZstdDictionaryMetadata metadata = ZstdDictionaryMetadata.tryParse(bytes);
        byte[] plain = metadata != null ? metadata.getPlainDictionary() : bytes;
        validateDictionary(plain);

        long embeddedId = Zstd.getDictIdFromDict(plain);
        System.out.println("=== Krypton Zstd Dictionary ===");
        System.out.println("File:          " + path);
        System.out.println("Format:        " + (metadata != null ? "Krypton wrapped" : "Plain Zstd dictionary"));
        System.out.println("Total size:    " + bytes.length + " bytes");
        System.out.println("Plain size:    " + plain.length + " bytes");
        System.out.println("Dictionary ID: " + embeddedId);
        if (metadata != null) {
            System.out.println("Samples:       " + metadata.getSampleCount());
            System.out.println("Created:       " + DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(metadata.getCreationTime())));
            System.out.println("SHA-256:       " + toHex(metadata.getHash(), metadata.getHash().length));
        }
        if (embeddedId == 0L) {
            System.out.println("Validation:    REJECTED - no dictionary ID (raw content); Krypton will not load it");
        } else if (metadata != null && metadata.getDictID() != embeddedId) {
            System.out.println("Validation:    REJECTED - metadata ID " + metadata.getDictID()
                    + " does not match the dictionary");
        } else {
            System.out.println("Validation:    loadable by zstd-jni");
        }
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

    // ------------------------------------------------------------------
    //  Arguments
    // ------------------------------------------------------------------

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
                DEFAULT_LARGE_SAMPLE_SHARE,
                DEFAULT_EVAL_SHARE,
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
        double largeSampleShare = DEFAULT_LARGE_SAMPLE_SHARE;
        double evalShare = DEFAULT_EVAL_SHARE;
        int evalSamples = DEFAULT_EVAL_SAMPLES;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String value = i + 1 < args.length ? args[i + 1] : null;
            switch (arg) {
                case "--samples" -> samplesDir = requirePath(arg, value);
                case "--output" -> outputPath = requirePath(arg, value);
                case "--dict-size" -> dictSize = requireInt(arg, value);
                case "--max-samples" -> maxSamples = requireInt(arg, value);
                case "--min-sample-bytes" -> minSampleBytes = requireInt(arg, value);
                case "--max-sample-bytes" -> maxSampleBytes = requireInt(arg, value);
                case "--large-sample-share" -> largeSampleShare = requireDouble(arg, value);
                case "--eval-share" -> evalShare = requireDouble(arg, value);
                case "--eval-samples" -> evalSamples = requireInt(arg, value);
                default -> throw new IllegalArgumentException("Unknown option: " + arg);
            }
            i++;
        }

        if (samplesDir == null || outputPath == null) {
            printUsageAndExit();
            throw new IllegalArgumentException("Missing --samples or --output");
        }
        return new TrainOptions(samplesDir, outputPath, dictSize, maxSamples, minSampleBytes, maxSampleBytes,
                largeSampleShare, evalShare, evalSamples);
    }

    private static String requireValue(String option, String value) {
        if (value == null || value.startsWith("--")) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return value;
    }

    private static Path requirePath(String option, String value) {
        return Paths.get(requireValue(option, value)).toAbsolutePath().normalize();
    }

    private static int requireInt(String option, String value) {
        return Integer.parseInt(requireValue(option, value));
    }

    private static double requireDouble(String option, String value) {
        return Double.parseDouble(requireValue(option, value));
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private static long totalBytes(List<byte[]> samples) {
        long total = 0L;
        for (byte[] sample : samples) {
            total += sample.length;
        }
        return total;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
        System.out.println("  java com.xinian.KryptonHybrid.tool.ZstdDictionaryTrainer --samples <dir> --output <path> [--dict-size n]"
                + " [--max-samples n] [--min-sample-bytes n] [--max-sample-bytes n] [--large-sample-share x]"
                + " [--eval-share x] [--eval-samples n]");
        System.out.println("  java com.xinian.KryptonHybrid.tool.ZstdDictionaryTrainer inspect <dictPath>");
        System.out.println();
        System.out.println("Defaults:");
        System.out.println("  dictSize=65536, maxSamples=8000, minSampleBytes=256 (compression threshold),"
                + " maxSampleBytes=16384 (dict_max_packet_bytes), largeSampleShare=0.25, evalShare=0.2,"
                + " evalSamples=1000");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  ./gradlew trainZstdDict -PsamplesDir=run/krypton_zstd_samples -PoutputDict=run/config/krypton_hybrid.zdict");
    }

    private record TrainOptions(
            Path samplesDir,
            Path outputPath,
            int dictSize,
            int maxSamples,
            int minSampleBytes,
            int maxSampleBytes,
            double largeSampleShare,
            double evalShare,
            int evalSamples
    ) {}
}
