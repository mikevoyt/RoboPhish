package com.bayapps.android.robophish.audio.offline;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.LongSupplier;

/** Lossless PCM16 output. An unfinished file never receives a playable RIFF header. */
public final class PcmWavWriter implements Closeable {
    public static final long SPACE_MARGIN = 32L * 1024 * 1024;
    public static final long MAX_DATA_BYTES = 0xffffffffL - 36;
    private final RandomAccessFile file;
    private final int sampleRate, channels;
    private final LongSupplier usableSpace;
    private byte[] buffer = new byte[0];
    private long frames, nextSpaceCheck;
    private boolean finished;

    public PcmWavWriter(File destination, int sampleRate, int channels, long expectedFrames) throws IOException {
        this(destination, sampleRate, channels, expectedFrames, () -> destination.getParentFile().getUsableSpace());
    }

    public PcmWavWriter(File destination, int sampleRate, int channels, long expectedFrames,
                        LongSupplier usableSpace) throws IOException {
        if (sampleRate < 8000 || sampleRate > 192000 || channels < 1 || channels > 2 || expectedFrames <= 0)
            throw new IllegalArgumentException("Unsupported output format");
        long bytes = Math.multiplyExact(expectedFrames, channels * 2L);
        if (bytes > MAX_DATA_BYTES) throw new IOException("Recording exceeds the WAV size limit");
        if (usableSpace.getAsLong() < bytes + 44 + SPACE_MARGIN) throw new IOException("Not enough free space for lossless audio");
        this.sampleRate = sampleRate; this.channels = channels; this.usableSpace = usableSpace;
        RandomAccessFile opened = new RandomAccessFile(destination, "rw");
        try { opened.setLength(0); opened.write(new byte[44]); }
        catch (IOException error) { opened.close(); throw error; }
        file = opened;
    }

    public void write(short[] pcm, int count) throws IOException {
        if (finished || count < 0 || count > pcm.length || count % channels != 0)
            throw new IllegalArgumentException("Invalid PCM output");
        long nextBytes = Math.multiplyExact(frames + count / channels, channels * 2L);
        if (nextBytes > MAX_DATA_BYTES) throw new IOException("Recording exceeds the WAV size limit");
        if (nextBytes >= nextSpaceCheck) {
            if (usableSpace.getAsLong() < SPACE_MARGIN + count * 2L) throw new IOException("Storage became full during processing");
            nextSpaceCheck = nextBytes + 1024 * 1024;
        }
        if (buffer.length < count * 2) buffer = new byte[count * 2];
        for (int i = 0; i < count; i++) { buffer[2 * i] = (byte) pcm[i]; buffer[2 * i + 1] = (byte) (pcm[i] >>> 8); }
        file.write(buffer, 0, count * 2);
        frames += count / channels;
    }

    public long finish(long expectedFrames) throws IOException {
        if (finished || frames <= 0 || frames != expectedFrames) throw new IOException("Processed recording has an incomplete frame count");
        long bytes = frames * channels * 2L;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R','I','F','F'}).putInt((int) (bytes + 36)).put(new byte[]{'W','A','V','E'});
        header.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short) 1).putShort((short) channels);
        header.putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        header.put(new byte[]{'d','a','t','a'}).putInt((int) bytes);
        file.seek(0); file.write(header.array()); file.getFD().sync(); finished = true;
        return bytes + 44;
    }

    public long getFrames() { return frames; }
    @Override public void close() throws IOException { file.close(); }
}
