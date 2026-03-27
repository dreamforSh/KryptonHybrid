package com.xinian.KryptonHybrid.shared.network.compression;

/**
 * Enumerates the packet-compression algorithms available in Krypton Hybrid.
 *
 * <p>The active algorithm is selected via the {@code compression.algorithm} key in
 * {@code config/krypton_fnp-common.toml} and stored in
 * {@link com.xinian.KryptonHybrid.shared.KryptonConfig#compressionAlgorithm}.
 * <strong>Both server and client must use the same algorithm.</strong>
 * Mixing algorithms across a connection will produce unreadable packet data and
 * immediately corrupt the session.</p>
 */
public enum CompressionAlgorithm {

    /**
     * Vanilla zlib (DEFLATE) compression, delegated to the Velocity natives library.
     * This is the Minecraft default and is always available.
     */
    ZLIB,

    /**
     * Zstandard (Zstd) compression via the zstd-jni native library.
     *
     * <p>Zstd typically achieves a significantly better compression ratio than zlib at
     * comparable or faster speeds, making it the best general-purpose choice for
     * servers that are bandwidth-constrained. Supports configurable compression levels
     * (1鈥?2) via {@link ZstdUtil}.</p>
     *
     * <p>Requires the zstd-jni native library to load successfully;
     * {@link ZstdUtil#AVAILABLE} must be {@code true}.</p>
     */
    ZSTD
}

