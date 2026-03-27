package com.xinian.KryptonHybrid.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.NetworkTrafficStats;

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
                    .then(Commands.literal("reset")
                        .executes(KryptonStatsCommand::executeReset)))
                .then(Commands.literal("packets")
                    .then(Commands.literal("bycount")
                        .executes(ctx -> executePacketsByCount(ctx, 10))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                            .executes(ctx -> executePacketsByCount(ctx, IntegerArgumentType.getInteger(ctx, "limit")))))
                    .then(Commands.literal("bybytes")
                        .executes(ctx -> executePacketsByBytes(ctx, 10))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                            .executes(ctx -> executePacketsByBytes(ctx, IntegerArgumentType.getInteger(ctx, "limit"))))))
                .then(Commands.literal("mods")
                    .then(Commands.literal("bycount")
                        .executes(ctx -> executeModsByCount(ctx, 10))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                            .executes(ctx -> executeModsByCount(ctx, IntegerArgumentType.getInteger(ctx, "limit")))))
                    .then(Commands.literal("bybytes")
                        .executes(ctx -> executeModsByBytes(ctx, 10))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                            .executes(ctx -> executeModsByBytes(ctx, IntegerArgumentType.getInteger(ctx, "limit"))))))
        );
    }

    private static MutableComponent lbl(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static MutableComponent val(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
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

        source.sendSuccess(() ->
            Component.literal("=== Krypton Hybrid Network Statistics ===")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);

        source.sendSuccess(() -> Component.empty()
            .append(lbl("Uptime: "))
            .append(val(elapsed + "s", ChatFormatting.WHITE))
            .append(lbl(" | Algorithm: "))
            .append(val(KryptonConfig.compressionAlgorithm.name(), ChatFormatting.AQUA)), false);

        source.sendSuccess(() -> Component.empty()
            .append(lbl("Packets  Sent: "))
            .append(val(String.format("%,d", packetsSent), ChatFormatting.GREEN))
            .append(val(String.format(" (%.1f/s)", (double) packetsSent / elapsed), ChatFormatting.DARK_GREEN))
            .append(lbl(" | Received: "))
            .append(val(String.format("%,d", packetsReceived), ChatFormatting.YELLOW))
            .append(val(String.format(" (%.1f/s)", (double) packetsReceived / elapsed), ChatFormatting.GOLD)), false);

        source.sendSuccess(() -> Component.empty()
            .append(lbl("Sent Original : "))
            .append(val(NetworkTrafficStats.formatBytes(bytesSentOrig), ChatFormatting.YELLOW))
            .append(val(String.format(" (%s/s)", NetworkTrafficStats.formatBytes(sendRateOrig)), ChatFormatting.GOLD)), false);

        source.sendSuccess(() -> Component.empty()
            .append(lbl("Sent Wire     : "))
            .append(val(NetworkTrafficStats.formatBytes(bytesSentWire), ChatFormatting.GREEN))
            .append(val(String.format(" (%s/s)", NetworkTrafficStats.formatBytes(sendRateWire)), ChatFormatting.DARK_GREEN)), false);

        source.sendSuccess(() -> Component.empty()
            .append(lbl("Received      : "))
            .append(val(NetworkTrafficStats.formatBytes(bytesReceived), ChatFormatting.AQUA))
            .append(val(String.format(" (%s/s)", NetworkTrafficStats.formatBytes(recvRate)), ChatFormatting.DARK_AQUA)), false);

        source.sendSuccess(() -> Component.empty()
            .append(lbl("Compression Ratio: "))
            .append(val(String.format("%.4f", ratio), ChatFormatting.LIGHT_PURPLE))
            .append(lbl(" (wire/orig, "))
            .append(val("lower = better", ChatFormatting.GREEN))
            .append(lbl(")  |  Bandwidth Saving: "))
            .append(val(String.format("%.2f%%", savingPct), ChatFormatting.GREEN)), false);

        return 1;
    }

    private static int executePacketsByCount(CommandContext<CommandSourceStack> ctx, int limit) {
        return executePacketsList(ctx, limit, true);
    }

    private static int executePacketsByBytes(CommandContext<CommandSourceStack> ctx, int limit) {
        return executePacketsList(ctx, limit, false);
    }

    private static int executePacketsList(CommandContext<CommandSourceStack> ctx, int limit, boolean byCount) {
        CommandSourceStack source = ctx.getSource();
        NetworkTrafficStats stats = NetworkTrafficStats.INSTANCE;

        List<Map.Entry<String, NetworkTrafficStats.TypeStats>> top =
            byCount ? stats.getTopByCount(limit) : stats.getTopByBytes(limit);

        long totalPackets = stats.getTotalTrackedTypePackets();
        long totalBytes = stats.getTotalTrackedTypeBytes();
        int typeCount = stats.getTrackedTypeCount();
        String sortLabel = byCount ? "Count" : "Bytes";

        source.sendSuccess(() -> Component.empty()
            .append(Component.literal("=== Krypton Hybrid Packet Types ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(val(String.format("(Top %d by ", limit), ChatFormatting.YELLOW))
            .append(val(sortLabel, ChatFormatting.WHITE))
            .append(val(String.format(" | %d types tracked)", typeCount), ChatFormatting.YELLOW))
            .append(Component.literal(" ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)), false);

        int[] rank = {1};
        for (Map.Entry<String, NetworkTrafficStats.TypeStats> entry : top) {
            NetworkTrafficStats.TypeStats ts = entry.getValue();
            double cntPct   = totalPackets == 0 ? 0.0 : 100.0 * ts.getCount() / totalPackets;
            double bytesPct = totalBytes   == 0 ? 0.0 : 100.0 * ts.getTotalBytes() / totalBytes;
            String key = entry.getKey();
            ChatFormatting nameColor = key.startsWith("custom:") ? ChatFormatting.YELLOW : ChatFormatting.AQUA;
            source.sendSuccess(() -> Component.empty()
                .append(val(String.format("#%-2d ", rank[0]++), ChatFormatting.DARK_GRAY))
                .append(val(truncate(key, 44), nameColor))
                .append(val(String.format("  %,8d", ts.getCount()), ChatFormatting.GREEN))
                .append(lbl(" pkt"))
                .append(val(String.format(" (%5.1f%%)", cntPct), ChatFormatting.DARK_GREEN))
                .append(val(String.format("  %9s", NetworkTrafficStats.formatBytes(ts.getTotalBytes())), ChatFormatting.AQUA))
                .append(val(String.format(" (%5.1f%%)", bytesPct), ChatFormatting.DARK_AQUA))
                .append(lbl("  avg "))
                .append(val(String.format("%.0f B", ts.getAvgBytes()), ChatFormatting.WHITE)), false);
        }

        source.sendSuccess(() -> Component.empty()
            .append(lbl("Total: "))
            .append(val(String.format("%,d", totalPackets), ChatFormatting.GREEN))
            .append(lbl(" packets | "))
            .append(val(NetworkTrafficStats.formatBytes(totalBytes), ChatFormatting.AQUA)), false);

        return 1;
    }

    private static int executeModsByCount(CommandContext<CommandSourceStack> ctx, int limit) {
        return executeModsList(ctx, limit, true);
    }

    private static int executeModsByBytes(CommandContext<CommandSourceStack> ctx, int limit) {
        return executeModsList(ctx, limit, false);
    }

    private static int executeModsList(CommandContext<CommandSourceStack> ctx, int limit, boolean byCount) {
        CommandSourceStack source = ctx.getSource();
        NetworkTrafficStats stats = NetworkTrafficStats.INSTANCE;

        List<Map.Entry<String, NetworkTrafficStats.TypeStats>> top =
            byCount ? stats.getTopModsByCount(limit) : stats.getTopModsByBytes(limit);

        long totalPackets = stats.getTotalTrackedTypePackets();
        long totalBytes   = stats.getTotalTrackedTypeBytes();
        int modCount      = stats.getTrackedModCount();
        String sortLabel  = byCount ? "Count" : "Bytes";

        source.sendSuccess(() -> Component.empty()
            .append(Component.literal("=== Krypton Hybrid Mod Traffic ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(val(String.format("(Top %d by ", limit), ChatFormatting.YELLOW))
            .append(val(sortLabel, ChatFormatting.WHITE))
            .append(val(String.format(" | %d mods)", modCount), ChatFormatting.YELLOW))
            .append(Component.literal(" ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)), false);

        int[] rank = {1};
        for (Map.Entry<String, NetworkTrafficStats.TypeStats> entry : top) {
            NetworkTrafficStats.TypeStats ts = entry.getValue();
            double cntPct   = totalPackets == 0 ? 0.0 : 100.0 * ts.getCount()      / totalPackets;
            double bytesPct = totalBytes   == 0 ? 0.0 : 100.0 * ts.getTotalBytes() / totalBytes;
            source.sendSuccess(() -> Component.empty()
                .append(val(String.format("#%-2d ", rank[0]++), ChatFormatting.DARK_GRAY))
                .append(val(String.format("%-20s", entry.getKey()), ChatFormatting.AQUA))
                .append(val(String.format("  %,8d", ts.getCount()), ChatFormatting.GREEN))
                .append(lbl(" pkt"))
                .append(val(String.format(" (%5.1f%%)", cntPct), ChatFormatting.DARK_GREEN))
                .append(val(String.format("  %9s", NetworkTrafficStats.formatBytes(ts.getTotalBytes())), ChatFormatting.AQUA))
                .append(val(String.format(" (%5.1f%%)", bytesPct), ChatFormatting.DARK_AQUA)), false);
        }

        source.sendSuccess(() -> Component.empty()
            .append(lbl("Total: "))
            .append(val(String.format("%,d", totalPackets), ChatFormatting.GREEN))
            .append(lbl(" packets | "))
            .append(val(NetworkTrafficStats.formatBytes(totalBytes), ChatFormatting.AQUA)), false);

        return 1;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 2) + "..";
    }

    private static int executeReset(CommandContext<CommandSourceStack> ctx) {
        NetworkTrafficStats.INSTANCE.reset();
        ctx.getSource().sendSuccess(() -> Component.empty()
            .append(val("Krypton Hybrid", ChatFormatting.AQUA))
            .append(lbl(" network statistics have been "))
            .append(val("reset", ChatFormatting.GREEN))
            .append(lbl(".")), true);
        return 1;
    }
}

