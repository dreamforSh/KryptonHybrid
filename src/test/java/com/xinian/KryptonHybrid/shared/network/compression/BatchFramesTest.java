package com.xinian.KryptonHybrid.shared.network.compression;

import com.github.luben.zstd.ZstdCompressCtx;
import com.xinian.KryptonHybrid.shared.KryptonConfig;
import com.xinian.KryptonHybrid.shared.network.stats.NetworkTrafficStats;
import com.xinian.KryptonHybrid.shared.network.util.VarIntUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round trips through {@link ZstdCompressEncoder} and {@link ZstdCompressDecoder} with batch frames. */
class BatchFramesTest {
    private static final int THRESHOLD = 256;

    private boolean savedEnabled;
    private int savedMaxPacketBytes;
    private int savedMaxBatchBytes;

    @BeforeEach
    void enableBatchFrames() {
        savedEnabled = KryptonConfig.batchFramesEnabled;
        savedMaxPacketBytes = KryptonConfig.batchFramesMaxPacketBytes;
        savedMaxBatchBytes = KryptonConfig.batchFramesMaxBatchBytes;
        KryptonConfig.batchFramesEnabled = true;
        KryptonConfig.batchFramesMaxPacketBytes = 16384;
        KryptonConfig.batchFramesMaxBatchBytes = 256 * 1024;
    }

    @AfterEach
    void restoreConfig() {
        KryptonConfig.batchFramesEnabled = savedEnabled;
        KryptonConfig.batchFramesMaxPacketBytes = savedMaxPacketBytes;
        KryptonConfig.batchFramesMaxBatchBytes = savedMaxBatchBytes;
    }

    @Test
    void heldPacketsLeaveAsOneSmallerFrameOnFlush() {
        List<byte[]> packets = entityPackets(200, 1);
        EmbeddedChannel server = server(true, null);
        long framesBefore = NetworkTrafficStats.INSTANCE.getBatchFrames();

        List<ChannelFuture> futures = writeAll(server, packets);
        assertNull(server.readOutbound(), "nothing may leave before the flush");
        assertFalse(futures.get(0).isDone());

        server.flush();
        List<ByteBuf> frames = frames(server);
        assertEquals(1, frames.size());
        assertTrue(sizeMarker(frames.get(0)) < 0, "expected a batch frame");
        int batchWire = withFrameLength(frames.get(0).readableBytes());
        assertTrue(batchWire < singleFramesWire(packets), "batch frame should be smaller than single frames");
        futures.forEach(f -> assertTrue(f.isSuccess()));
        assertEquals(framesBefore + 1, NetworkTrafficStats.INSTANCE.getBatchFrames());

        assertPackets(packets, decode(client(true, null), frames));
    }

    @Test
    void packetFlushedOnItsOwnIsEncodedAsWithoutBatching() {
        byte[] small = entityPackets(1, 2).get(0);
        byte[] medium = repetitive(2000, 3);

        for (byte[] packet : List.of(small, medium)) {
            EmbeddedChannel batching = server(true, null);
            EmbeddedChannel plain = server(false, null);
            batching.writeAndFlush(direct(packet));
            plain.writeAndFlush(direct(packet));
            assertArrayEquals(bytes(frames(plain)), bytes(frames(batching)));
        }
    }

    @Test
    void largePacketLeavesAloneAfterThePacketsHeldBeforeIt() {
        List<byte[]> packets = new ArrayList<>(entityPackets(30, 4));
        packets.add(repetitive(20000, 5));
        packets.addAll(entityPackets(30, 6));
        EmbeddedChannel server = server(true, null);

        writeAll(server, packets);
        server.flush();
        List<ByteBuf> frames = frames(server);

        assertEquals(3, frames.size());
        assertTrue(sizeMarker(frames.get(0)) < 0);
        assertTrue(sizeMarker(frames.get(1)) > 0, "the large packet is a single compressed frame");
        assertTrue(sizeMarker(frames.get(2)) < 0);
        assertPackets(packets, decode(client(true, null), frames));
    }

    @Test
    void fullBatchIsSentAndANewOneStarted() {
        KryptonConfig.batchFramesMaxBatchBytes = 4096;
        List<byte[]> packets = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            packets.add(repetitive(200, 100 + i));
        }
        EmbeddedChannel server = server(true, null);

        writeAll(server, packets);
        server.flush();
        List<ByteBuf> frames = frames(server);

        assertTrue(frames.size() > 1);
        for (ByteBuf frame : frames) {
            int marker = sizeMarker(frame);
            assertTrue(marker < 0 && -marker <= 4096, "batch content within max_batch_bytes");
        }
        assertPackets(packets, decode(client(true, null), frames));
    }

    @Test
    void incompressiblePacketsFallBackToSingleFrames() {
        Random random = new Random(7);
        List<byte[]> packets = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            byte[] packet = new byte[100];
            random.nextBytes(packet);
            packets.add(packet);
        }
        EmbeddedChannel server = server(true, null);

        writeAll(server, packets);
        server.flush();
        List<ByteBuf> frames = frames(server);

        assertEquals(packets.size(), frames.size());
        frames.forEach(frame -> assertEquals(0, sizeMarker(frame), "raw single frame"));
        assertPackets(packets, decode(client(true, null), frames));
    }

    @Test
    void tinyBatchFallsBackToSingleFrames() {
        // Two small packets: the batch marker and frame header cost more than they save.
        List<byte[]> packets = entityPackets(2, 13);
        EmbeddedChannel server = server(true, null);

        writeAll(server, packets);
        server.flush();
        List<ByteBuf> frames = frames(server);

        assertEquals(2, frames.size());
        assertPackets(packets, decode(client(true, null), frames));
    }

    @Test
    void disablingAtRuntimeSendsHeldPacketsFirst() {
        List<byte[]> packets = entityPackets(31, 8);
        EmbeddedChannel server = server(true, null);

        writeAll(server, packets.subList(0, 30));
        KryptonConfig.batchFramesEnabled = false;
        server.write(direct(packets.get(30)));
        server.flush();
        List<ByteBuf> frames = frames(server);

        assertEquals(2, frames.size());
        assertTrue(sizeMarker(frames.get(0)) < 0);
        assertEquals(0, sizeMarker(frames.get(1)));
        assertPackets(packets, decode(client(true, null), frames));
    }

    @Test
    void closingFailsHeldWrites() {
        EmbeddedChannel server = server(true, null);
        List<ChannelFuture> futures = writeAll(server, entityPackets(2, 9));

        server.close();

        for (ChannelFuture future : futures) {
            assertTrue(future.isDone());
            assertFalse(future.isSuccess());
            assertInstanceOf(ClosedChannelException.class, future.cause());
        }
    }

    @Test
    void peerCloseFailsHeldWritesWhileThePipelineIsIntact() {
        List<Throwable> caught = new ArrayList<>();
        EmbeddedChannel server = server(true, null);
        // Stands in for vanilla's Connection, which ignores failures once the channel is closed.
        server.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                caught.add(cause);
            }
        });
        List<ChannelFuture> futures = writeAll(server, entityPackets(2, 14));
        // Vanilla adds this to every packet it sends.
        futures.forEach(f -> f.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE));

        // A peer close or an I/O error closes through the channel's unsafe, not the pipeline.
        server.unsafe().close(server.voidPromise());
        server.runPendingTasks();

        futures.forEach(f -> assertInstanceOf(ClosedChannelException.class, f.cause()));
        assertEquals(2, caught.size(), "failures must reach the pipeline before it is torn down");
        server.checkException(); // nothing reached the tail of the pipeline
    }

    @Test
    void dictionaryRoundTrip() {
        byte[] dictionary = concat(entityPackets(400, 10));
        List<byte[]> packets = entityPackets(150, 11);
        EmbeddedChannel server = server(true, dictionary);

        writeAll(server, packets);
        server.flush();
        List<ByteBuf> frames = frames(server);

        assertEquals(1, frames.size());
        assertTrue(sizeMarker(frames.get(0)) < 0);
        assertPackets(packets, decode(client(true, dictionary), frames));
    }

    @Test
    void decoderRejectsBatchFrameWhenNotAgreed() {
        EmbeddedChannel server = server(true, null);
        writeAll(server, entityPackets(20, 12));
        server.flush();
        List<ByteBuf> frames = frames(server);

        EmbeddedChannel client = client(false, null);
        assertThrows(DecoderException.class, () -> client.writeInbound(frames.get(0)));
        assertNull(client.readInbound());
    }

    @Test
    void malformedBatchFramePassesNothingOn() {
        ByteBuf content = Unpooled.buffer();
        VarIntUtil.writeVarInt(content, 3);
        content.writeBytes(new byte[] {1, 2, 3});
        VarIntUtil.writeVarInt(content, 100); // longer than what is left
        content.writeByte(9);
        byte[] raw = ByteBufUtil.getBytes(content);

        ByteBuf frame = Unpooled.buffer();
        VarIntUtil.writeVarInt(frame, -raw.length);
        try (ZstdCompressCtx zstd = ZstdUtil.createCompressor()) {
            frame.writeBytes(zstd.compress(raw));
        }

        EmbeddedChannel client = client(true, null);
        assertThrows(DecoderException.class, () -> client.writeInbound(frame));
        assertNull(client.readInbound());
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private static EmbeddedChannel server(boolean batchFrames, byte[] dictionary) {
        return new EmbeddedChannel(new ZstdCompressEncoder(THRESHOLD, ZstdUtil.createCompressor(), dictionary,
                batchFrames));
    }

    private static EmbeddedChannel client(boolean batchFrames, byte[] dictionary) {
        return new EmbeddedChannel(new ZstdCompressDecoder(THRESHOLD, false, ZstdUtil.createDecompressor(),
                dictionary == null ? null : ZstdUtil.createDictionaryDecompressor(dictionary), batchFrames));
    }

    private static List<ChannelFuture> writeAll(EmbeddedChannel server, List<byte[]> packets) {
        List<ChannelFuture> futures = new ArrayList<>();
        for (byte[] packet : packets) {
            futures.add(server.write(direct(packet)));
        }
        return futures;
    }

    /** Frames the server wrote, in order, without the frame length (no prepender here). */
    private static List<ByteBuf> frames(EmbeddedChannel server) {
        List<ByteBuf> frames = new ArrayList<>();
        ByteBuf frame;
        while ((frame = server.readOutbound()) != null) {
            frames.add(frame);
        }
        return frames;
    }

    private static List<byte[]> decode(EmbeddedChannel client, List<ByteBuf> frames) {
        for (ByteBuf frame : frames) {
            client.writeInbound(frame);
        }
        List<byte[]> packets = new ArrayList<>();
        ByteBuf packet;
        while ((packet = client.readInbound()) != null) {
            packets.add(ByteBufUtil.getBytes(packet));
            packet.release();
        }
        return packets;
    }

    private static void assertPackets(List<byte[]> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size(), "packet count");
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), actual.get(i), "packet " + i);
        }
    }

    /** The VarInt size field at the start of a frame: 0 raw, positive compressed, negative batch. */
    private static int sizeMarker(ByteBuf frame) {
        return VarIntUtil.readVarInt(frame.duplicate());
    }

    private static byte[] bytes(List<ByteBuf> frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (ByteBuf frame : frames) {
            out.writeBytes(ByteBufUtil.getBytes(frame));
            frame.release();
        }
        return out.toByteArray();
    }

    private static ByteBuf direct(byte[] bytes) {
        return Unpooled.directBuffer(bytes.length).writeBytes(bytes);
    }

    /** Wire bytes of {@code packets} sent one frame each, as without batching. */
    private static int singleFramesWire(List<byte[]> packets) {
        EmbeddedChannel plain = server(false, null);
        int wire = 0;
        for (byte[] packet : packets) {
            plain.writeAndFlush(direct(packet));
            for (ByteBuf frame : frames(plain)) {
                wire += withFrameLength(frame.readableBytes());
                frame.release();
            }
        }
        return wire;
    }

    /** A frame's size on the wire, with the VarInt frame length the prepender adds. */
    private static int withFrameLength(int frameBytes) {
        return VarIntUtil.getVarIntLength(frameBytes) + frameBytes;
    }

    private static byte[] concat(List<byte[]> packets) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        packets.forEach(out::writeBytes);
        return out.toByteArray();
    }

    /** Packets shaped like entity movement updates: id, entity id, small deltas, rotation, flag. */
    private static List<byte[]> entityPackets(int count, long seed) {
        Random random = new Random(seed);
        List<byte[]> packets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ByteBuf buf = Unpooled.buffer();
            buf.writeByte(0x28 + random.nextInt(3));
            VarIntUtil.writeVarInt(buf, 1000 + random.nextInt(40));
            for (int axis = 0; axis < 3; axis++) {
                buf.writeShort(random.nextInt(64) - 32);
            }
            buf.writeByte(random.nextInt(256));
            buf.writeByte(random.nextInt(16));
            buf.writeBoolean(random.nextInt(4) != 0);
            packets.add(ByteBufUtil.getBytes(buf));
        }
        return packets;
    }

    /** A compressible packet of {@code size} bytes. */
    private static byte[] repetitive(int size, long seed) {
        Random random = new Random(seed);
        byte[] pattern = new byte[16];
        random.nextBytes(pattern);
        byte[] packet = new byte[size];
        for (int i = 0; i < size; i++) {
            packet[i] = pattern[i % pattern.length];
        }
        return packet;
    }
}
