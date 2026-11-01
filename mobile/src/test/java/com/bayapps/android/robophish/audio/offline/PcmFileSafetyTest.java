package com.bayapps.android.robophish.audio.offline;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class PcmFileSafetyTest {
    @Test public void waveStoresExactLittleEndianFramesAndDurableHeader() throws Exception {
        File file = File.createTempFile("enhanced-test", ".part");
        try {
            try (PcmWavWriter writer = new PcmWavWriter(file, 44100, 2, 3, () -> Long.MAX_VALUE)) {
                writer.write(new short[]{Short.MIN_VALUE, Short.MAX_VALUE}, 2);
                writer.write(new short[]{1,-1,100,-100}, 4);
                assertEquals(56, writer.finish(3));
            }
            byte[] bytes = Files.readAllBytes(file.toPath());
            ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals("RIFF", new String(bytes, 0, 4, "US-ASCII"));
            assertEquals("WAVE", new String(bytes, 8, 4, "US-ASCII"));
            assertEquals(48, data.getInt(4));
            assertEquals(2, data.getShort(22)); assertEquals(44100, data.getInt(24));
            assertEquals(12, data.getInt(40));
            assertEquals(Short.MIN_VALUE, data.getShort(44)); assertEquals(Short.MAX_VALUE, data.getShort(46));
            assertEquals(-100, data.getShort(54));
        } finally { file.delete(); }
    }

    @Test public void shortOutputNeverReceivesPlayableHeader() throws Exception {
        File file = File.createTempFile("incomplete-test", ".part");
        try {
            try (PcmWavWriter writer = new PcmWavWriter(file, 48000, 1, 10, () -> Long.MAX_VALUE)) {
                writer.write(new short[]{1,2}, 2);
                assertThrows(IOException.class, () -> writer.finish(10));
            }
            assertEquals(0, Files.readAllBytes(file.toPath())[0]);
        } finally { file.delete(); }
    }

    @Test public void freeSpaceAndRiffBoundsRejectBeforeWriting() throws Exception {
        File file = File.createTempFile("space-test", ".part");
        try {
            Files.write(file.toPath(), new byte[]{9,8,7});
            assertThrows(IOException.class, () -> new PcmWavWriter(file, 44100, 2, 100, () -> 0));
            assertArrayEquals(new byte[]{9,8,7}, Files.readAllBytes(file.toPath()));
            assertThrows(IOException.class, () -> new PcmWavWriter(file, 44100, 2, 0xffffffffL, () -> Long.MAX_VALUE));
        } finally { file.delete(); }
    }

    @Test public void storageExhaustionMidRenderDoesNotFinalizeFile() throws Exception {
        File file = File.createTempFile("full-test", ".part");
        AtomicLong free = new AtomicLong(Long.MAX_VALUE);
        try {
            try (PcmWavWriter writer = new PcmWavWriter(file, 44100, 1, 2, free::get)) {
                free.set(0);
                assertThrows(IOException.class, () -> writer.write(new short[]{1,2}, 2));
                assertEquals(0, writer.getFrames());
            }
            assertEquals(0, Files.readAllBytes(file.toPath())[0]);
        } finally { file.delete(); }
    }

    @Test public void gaplessTrimmingIsIndependentOfChunkBoundariesAndPreservesChannels() throws Exception {
        short[] input = new short[40];
        for (short i = 0; i < input.length; i++) input[i] = i;
        for (int chunk : new int[]{2,4,6,10,40}) {
            PcmGaplessTrimmer trimmer = new PcmGaplessTrimmer(2, 3, 4);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (int offset = 0; offset < input.length; offset += chunk) {
                int count = Math.min(chunk, input.length - offset);
                short[] block = java.util.Arrays.copyOfRange(input, offset, offset + count);
                trimmer.accept(block, count, (data, n) -> { for (int i = 0; i < n; i++) output.write(data[i]); });
            }
            assertEquals(13, trimmer.finish());
            byte[] expected = new byte[26];
            for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i + 6);
            assertArrayEquals(expected, output.toByteArray());
        }
    }

    @Test public void impossibleGaplessMetadataRejectsTruncatedAudio() throws Exception {
        PcmGaplessTrimmer trimmer = new PcmGaplessTrimmer(2, 10, 10);
        trimmer.accept(new short[8], 8, (data, n) -> fail("Truncated data must not be emitted"));
        assertThrows(IOException.class, trimmer::finish);
    }
}
