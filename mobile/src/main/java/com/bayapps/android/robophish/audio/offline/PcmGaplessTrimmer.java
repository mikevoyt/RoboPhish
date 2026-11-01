package com.bayapps.android.robophish.audio.offline;

import java.io.IOException;

/** Trims codec priming and final padding once, independent of decoder buffer boundaries. */
public final class PcmGaplessTrimmer {
    @FunctionalInterface public interface Sink { void write(short[] data, int samples) throws IOException; }
    private final int channels;
    private final short[] tail;
    private long skip;
    private int filled, cursor;
    private long frames;

    public PcmGaplessTrimmer(int channels, int delayFrames, int paddingFrames) {
        if (channels < 1 || channels > 2 || delayFrames < 0 || paddingFrames < 0 || paddingFrames > 192000)
            throw new IllegalArgumentException("Invalid gapless metadata");
        this.channels = channels;
        this.skip = (long) delayFrames * channels;
        this.tail = new short[paddingFrames * channels];
    }

    public void accept(short[] data, int count, Sink sink) throws IOException {
        if (count < 0 || count > data.length || count % channels != 0)
            throw new IllegalArgumentException("Incomplete PCM frames");
        int start = (int) Math.min(skip, count);
        skip -= start;
        int out = 0;
        for (int i = start; i < count; i++) {
            short sample = data[i];
            if (tail.length == 0) data[out++] = sample;
            else if (filled < tail.length) tail[filled++] = sample;
            else {
                data[out++] = tail[cursor];
                tail[cursor] = sample;
                cursor = (cursor + 1) % tail.length;
            }
        }
        if (out > 0) { sink.write(data, out); frames += out / channels; }
    }

    public long finish() throws IOException {
        if (skip != 0 || filled != tail.length || frames == 0)
            throw new IOException("Recording is shorter than its audio trim metadata");
        return frames;
    }
}
