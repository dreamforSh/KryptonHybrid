package com.xinian.KryptonHybrid.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.NetworkTrafficStats;
import com.xinian.KryptonHybrid.shared.network.payload.StatsSnapshotPayload;
import com.xinian.KryptonHybrid.shared.network.security.SecurityMetrics;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Map;

public final class KryptonStatsCommand {

    private KryptonStatsCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("krypton")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("stats")
                    .then(Commands.literal("show")
                        .executes(KryptonStatsCommand::executeShow))
                    .then(Commands.literal("gui")
                        .executes(KryptonStatsCommand::executeGui))
                    .then(Commands.literal("reset")
                        .executes(KryptonStatsCommand::executeReset)))
                .then(Commands.literal("packets")
                    .then(Commands.literal("bycount")
                        .executes(ctx -> executePacketsList(ctx, 10, true))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                            .executes(ctx -> executePacketsList(ctx, IntegerArgumentType.getInteger(ctx, "limit"), true))))
                    .then(Commands.literal("bybytes")
                        .executes(ctx -> executePacketsList(ctx, 10, false))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                            .executes(ctx -> executePacketsList(ctx, IntegerArgumentType.getInteger(ctx, "limit"), false))))
                    .then(Commands.literal("bywire")
                        .executes(ctx -> executePacketsWireList(ctx, 10))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                            .executes(ctx -> executePacketsWireList(ctx, IntegerArgumentType.getInteger(ctx, "limit"))))))
                .then(Commands.literal("mods")
                    .then(Commands.literal("bycount")
                        .executes(ctx -> executeModsList(ctx, 10, true))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                            .executes(ctx -> executeModsList(ctx, IntegerArgumentType.getInteger(ctx, "limit"), true))))
                    .then(Commands.literal("bybytes")
                        .executes(ctx -> executeModsList(ctx, 10, false))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                            .executes(ctx -> executeModsList(ctx, IntegerArgumentType.getInteger(ctx, "limit"), false))))
                    .then(Commands.literal("bywire")
                        .executes(ctx -> executeModsWireList(ctx, 10))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                            .executes(ctx -> executeModsWireList(ctx, IntegerArgumentType.getInteger(ctx, "limit"))))))
                .then(Commands.literal("security")
                    .then(Commands.literal("status")
                        .executes(KryptonStatsCommand::executeSecurityStatus)))
        );
    }

    private static MutableComponent t(String key, Object... args) {
        return Component.translatable(key, args);
    }

    private static int executeShow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        NetworkTrafficStats stats = NetworkTrafficStats.INSTANCE;

        long elapsed = stats.getElapsedSeconds();
        long packetsSent = stats.getPacketsSent();
        long packetsReceived = stats.getPacketsReceived();
        long bytesSentOrig = stats.getBytesSentOriginal();
        long bytesSentWire = stats.getBytesSentWire();
        long bytesReceived = stats.getBytesReceived();
        double savingPct = stats.getCompressionSavingPercent();
        double ratio = stats.getCompressionRatio();

        long sendRateOrig = bytesSentOrig / elapsed;
        long sendRateWire = bytesSentWire / elapsed;
        long recvRate = bytesReceived / elapsed;

        source.sendSuccess(() -> t("command.krypton_hybrid.stats.header")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.stats.uptime_algo",
                String.valueOf(elapsed),
                KryptonConfig.compressionAlgorithm.name()), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.stats.packets",
                String.format("%,d", packetsSent),
                String.format("%.1f", (double) packetsSent / elapsed),
                String.format("%,d", packetsReceived),
                String.format("%.1f", (double) packetsReceived / elapsed)), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.stats.sent_original",
                NetworkTrafficStats.formatBytes(bytesSentOrig),
                NetworkTrafficStats.formatBytes(sendRateOrig)), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.stats.sent_wire",
                NetworkTrafficStats.formatBytes(bytesSentWire),
                NetworkTrafficStats.formatBytes(sendRateWire)), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.stats.received",
                NetworkTrafficStats.formatBytes(bytesReceived),
                NetworkTrafficStats.formatBytes(recvRate)), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.stats.compression",
                String.format("%.4f", ratio),
                String.format("%.2f", savingPct)), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.stats.bundles",
                String.format("%,d", stats.getBundlesEmitted()),
                String.format("%.2f", stats.getAvgBundleSize()),
                String.format("%.1f", stats.getBundleHitRate() * 100.0),
                String.format("%,d", stats.getCoalesceDroppedPackets())), false);

        return 1;
    }

    private static int executeGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        StatsSnapshotPayload snap = StatsSnapshotPayload.current();
        PacketDistributor.sendToPlayer(player, snap);
        return 1;
    }

    private static int executePacketsList(CommandContext<CommandSourceStack> ctx, int limit, boolean byCount) {
        CommandSourceStack source = ctx.getSource();
        NetworkTrafficStats stats = NetworkTrafficStats.INSTANCE;

        List<Map.Entry<String, NetworkTrafficStats.TypeStats>> top =
            byCount ? stats.getTopByCount(limit) : stats.getTopByBytes(limit);

        long totalPackets = stats.getTotalTrackedTypePackets();
        long totalBytes = stats.getTotalTrackedTypeBytes();
        int typeCount = stats.getTrackedTypeCount();
        Component sortLabel = byCount
                ? Component.translatable("command.krypton_hybrid.sort.count")
                : Component.translatable("command.krypton_hybrid.sort.bytes");

        source.sendSuccess(() -> t("command.krypton_hybrid.packets.header",
                limit, sortLabel, typeCount)
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);

        int[] rank = {1};
        for (Map.Entry<String, NetworkTrafficStats.TypeStats> entry : top) {
            NetworkTrafficStats.TypeStats ts = entry.getValue();
            double cntPct   = totalPackets == 0 ? 0.0 : 100.0 * ts.getCount() / totalPackets;
            double bytesPct = totalBytes   == 0 ? 0.0 : 100.0 * ts.getTotalBytes() / totalBytes;
            String key = entry.getKey();
            ChatFormatting nameColor = key.startsWith("custom:") ? ChatFormatting.YELLOW : ChatFormatting.AQUA;
            int currentRank = rank[0]++;
            source.sendSuccess(() -> t("command.krypton_hybrid.packets.row",
                    String.format("%-2d", currentRank),
                    Component.literal(truncate(key, 44)).withStyle(nameColor),
                    String.format("%,d", ts.getCount()),
                    String.format("%.1f", cntPct),
                    NetworkTrafficStats.formatBytes(ts.getTotalBytes()),
                    String.format("%.1f", bytesPct),
                    String.format("%.0f", ts.getAvgBytes())), false);
        }

        source.sendSuccess(() -> t("command.krypton_hybrid.list.total",
                String.format("%,d", totalPackets),
                NetworkTrafficStats.formatBytes(totalBytes)), false);

        return 1;
    }

    private static int executePacketsWireList(CommandContext<CommandSourceStack> ctx, int limit) {
        CommandSourceStack source = ctx.getSource();
        NetworkTrafficStats stats = NetworkTrafficStats.INSTANCE;

        List<Map.Entry<String, NetworkTrafficStats.TypeStats>> top = stats.getTopByWireBytes(limit);
        long totalPackets = stats.getTotalTrackedTypeWirePackets();
        long totalBytes = stats.getTotalTrackedTypeWireBytes();

        source.sendSuccess(() -> Component.literal("Krypton packets by compressed wire bytes (top " + limit + ")")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);

        int[] rank = {1};
        for (Map.Entry<String, NetworkTrafficStats.TypeStats> entry : top) {
            NetworkTrafficStats.TypeStats ts = entry.getValue();
            double bytesPct = totalBytes == 0 ? 0.0 : 100.0 * ts.getTotalBytes() / totalBytes;
            String key = entry.getKey();
            ChatFormatting nameColor = key.startsWith("custom:") ? ChatFormatting.YELLOW : ChatFormatting.AQUA;
            int currentRank = rank[0]++;
            source.sendSuccess(() -> Component.literal(String.format(
                    "%-2d %s count=%,d wire=%s %.1f%% avg=%s",
                    currentRank,
                    truncate(key, 44),
                    ts.getCount(),
                    NetworkTrafficStats.formatBytes(ts.getTotalBytes()),
                    bytesPct,
                    NetworkTrafficStats.formatBytes((long) ts.getAvgBytes())
            )).withStyle(nameColor), false);
        }

        source.sendSuccess(() -> Component.literal("Total: "
                + String.format("%,d", totalPackets)
                + " packets, "
                + NetworkTrafficStats.formatBytes(totalBytes)), false);

        return 1;
    }

    private static int executeModsList(CommandContext<CommandSourceStack> ctx, int limit, boolean byCount) {
        CommandSourceStack source = ctx.getSource();
        NetworkTrafficStats stats = NetworkTrafficStats.INSTANCE;

        List<Map.Entry<String, NetworkTrafficStats.TypeStats>> top =
            byCount ? stats.getTopModsByCount(limit) : stats.getTopModsByBytes(limit);

        long totalPackets = stats.getTotalTrackedTypePackets();
        long totalBytes   = stats.getTotalTrackedTypeBytes();
        int modCount      = stats.getTrackedModCount();
        Component sortLabel = byCount
                ? Component.translatable("command.krypton_hybrid.sort.count")
                : Component.translatable("command.krypton_hybrid.sort.bytes");

        source.sendSuccess(() -> t("command.krypton_hybrid.mods.header",
                limit, sortLabel, modCount)
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);

        int[] rank = {1};
        for (Map.Entry<String, NetworkTrafficStats.TypeStats> entry : top) {
            NetworkTrafficStats.TypeStats ts = entry.getValue();
            double cntPct   = totalPackets == 0 ? 0.0 : 100.0 * ts.getCount()      / totalPackets;
            double bytesPct = totalBytes   == 0 ? 0.0 : 100.0 * ts.getTotalBytes() / totalBytes;
            int currentRank = rank[0]++;
            source.sendSuccess(() -> t("command.krypton_hybrid.mods.row",
                    String.format("%-2d", currentRank),
                    String.format("%-20s", entry.getKey()),
                    String.format("%,d", ts.getCount()),
                    String.format("%.1f", cntPct),
                    NetworkTrafficStats.formatBytes(ts.getTotalBytes()),
                    String.format("%.1f", bytesPct)), false);
        }

        source.sendSuccess(() -> t("command.krypton_hybrid.list.total",
                String.format("%,d", totalPackets),
                NetworkTrafficStats.formatBytes(totalBytes)), false);

        return 1;
    }

    private static int executeModsWireList(CommandContext<CommandSourceStack> ctx, int limit) {
        CommandSourceStack source = ctx.getSource();
        NetworkTrafficStats stats = NetworkTrafficStats.INSTANCE;

        List<Map.Entry<String, NetworkTrafficStats.TypeStats>> top = stats.getTopModsByWireBytes(limit);
        long totalPackets = stats.getTotalTrackedModWirePackets();
        long totalBytes = stats.getTotalTrackedModWireBytes();

        source.sendSuccess(() -> Component.literal("Krypton mods by compressed wire bytes (top " + limit + ")")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);

        int[] rank = {1};
        for (Map.Entry<String, NetworkTrafficStats.TypeStats> entry : top) {
            NetworkTrafficStats.TypeStats ts = entry.getValue();
            double bytesPct = totalBytes == 0 ? 0.0 : 100.0 * ts.getTotalBytes() / totalBytes;
            int currentRank = rank[0]++;
            source.sendSuccess(() -> Component.literal(String.format(
                    "%-2d %-20s count=%,d wire=%s %.1f%%",
                    currentRank,
                    truncate(entry.getKey(), 20),
                    ts.getCount(),
                    NetworkTrafficStats.formatBytes(ts.getTotalBytes()),
                    bytesPct
            )).withStyle(ChatFormatting.AQUA), false);
        }

        source.sendSuccess(() -> Component.literal("Total: "
                + String.format("%,d", totalPackets)
                + " packets, "
                + NetworkTrafficStats.formatBytes(totalBytes)), false);

        return 1;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 2) + "..";
    }

    private static int executeSecurityStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        SecurityMetrics m = SecurityMetrics.INSTANCE;

        source.sendSuccess(() -> t("command.krypton_hybrid.security.header")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);

        source.sendSuccess(() -> {
            MutableComponent state = Component.translatable(KryptonConfig.securityEnabled
                    ? "command.krypton_hybrid.security.state.on"
                    : "command.krypton_hybrid.security.state.off")
                    .withStyle(KryptonConfig.securityEnabled
                            ? ChatFormatting.GREEN : ChatFormatting.RED);
            return t("command.krypton_hybrid.security.enabled", state);
        }, false);

        source.sendSuccess(() -> t("command.krypton_hybrid.security.conn_rate_limited",
                String.valueOf(m.getConnectionsRateLimited())), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.security.pkts_size_rejected",
                String.valueOf(m.getPacketsSizeRejected())), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.security.read_limit_rejected",
                String.valueOf(m.getReadLimitRejected())), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.security.null_frames_dropped",
                String.valueOf(m.getNullFramesDropped())), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.security.decomp_bombs",
                String.valueOf(m.getDecompressionBombs())), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.security.handshakes_rejected",
                String.valueOf(m.getHandshakesRejected())), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.security.timeouts",
                String.valueOf(m.getTimeouts())), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.security.anomaly_disconnects",
                String.valueOf(m.getAnomalyDisconnects())), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.security.anomaly_events",
                String.valueOf(m.getAnomalyEvents())), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.security.writes_dropped",
                String.valueOf(m.getWritesDropped())), false);
        source.sendSuccess(() -> t("command.krypton_hybrid.security.watermark_breaches",
                String.valueOf(m.getWatermarkBreaches())), false);

        return 1;
    }

    private static int executeReset(CommandContext<CommandSourceStack> ctx) {
        NetworkTrafficStats.INSTANCE.reset();
        ctx.getSource().sendSuccess(() ->
                t("command.krypton_hybrid.stats.reset")
                        .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }
}

