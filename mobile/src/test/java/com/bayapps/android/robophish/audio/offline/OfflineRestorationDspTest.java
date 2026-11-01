package com.bayapps.android.robophish.audio.offline;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

public class OfflineRestorationDspTest {
    private static final int SR = 44100;

    @Test
    public void emptyAnalysisRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new OfflineRestorationProfile.Analyzer(SR, 2).finish());
    }

    @Test
    public void silenceAndSingleToneAbstain() {
        short[] silence = new short[SR * 4];
        assertTrue(analyze(silence, 1).passthrough);
        short[] tone = new short[SR * 4];
        for (int n = 0; n < tone.length; n++)
            tone[n] = (short) Math.round(12000 * Math.sin(n * 2 * Math.PI * 440 / SR));
        assertTrue(analyze(tone, 1).passthrough);
    }

    @Test
    public void noConfidencePreservesEveryPcmValueExactly() throws Exception {
        short[] samples = new short[65536];
        for (int i = 0; i < samples.length; i++) samples[i] = (short) (i - 32768);
        assertArrayEquals(samples, render(samples, 1, profile(1, 0), 997));
    }

    @Test
    public void overlapAddPreservesGainAndTimingIncludingShortTail() throws Exception {
        for (int frames : new int[] {1, 13, 511, 512, 513, 2047, 2048, 2049, 12017}) {
            short[] samples = new short[frames * 2];
            Random random = new Random(frames);
            for (int i = 0; i < samples.length; i++)
                samples[i] = (short) (random.nextInt(24001) - 12000);
            short[] out = render(samples, 2, profile(2, -3), 37);
            assertEquals(samples.length, out.length);
            for (int i = 0; i < out.length; i++)
                assertEquals(samples[i] * Math.pow(10, -3 / 20.0), out[i], 1.05);
        }
    }

    @Test
    public void processingIndependentOfDecoderChunkSizes() throws Exception {
        short[] samples = complexStereo(SR * 2 + 117);
        OfflineRestorationProfile profile = shapedProfile();
        assertArrayEquals(render(samples, 2, profile, 1), render(samples, 2, profile, 8192));
        assertArrayEquals(render(samples, 2, profile, 317), render(samples, 2, profile, 8192));
    }

    @Test
    public void adaptivePathEngagesAndRespectsAttenuationBound() throws Exception {
        short[] samples = complexStereo(SR * 3);
        OfflineRestorationDsp dsp = new OfflineRestorationDsp(SR, 2, shapedProfile());
        OfflineRestorationDsp.PcmSink sink = (data, count) -> {};
        dsp.process(samples, samples.length, sink);
        dsp.finish(sink);
        assertTrue("Test must exercise spectral dynamics, not just a static filter",
            dsp.getDynamicWindows() > 20);
        assertTrue(dsp.getLargestDynamicReductionDb() > 0.2);
        assertTrue(dsp.getLargestDynamicReductionDb() <= 1.5);
        assertEquals(0, dsp.getClippedSampleCount());
    }

    @Test
    public void commonMaskPreservesDualMonoAndOppositePolarity() throws Exception {
        short[] samples = complexStereo(SR + 29);
        for (int i = 0; i < samples.length; i += 2) samples[i + 1] = (short) -samples[i];
        short[] out = render(samples, 2, shapedProfile(), 719);
        for (int i = 0; i < out.length; i += 2) assertEquals(-out[i], out[i + 1], 1);
        for (int i = 0; i < samples.length; i += 2) samples[i + 1] = samples[i];
        out = render(samples, 2, shapedProfile(), 719);
        for (int i = 0; i < out.length; i += 2) assertEquals(out[i], out[i + 1]);
    }

    @Test
    public void profileDefensiveCopiesAndFormatGuard() {
        double[] gain = new double[26], threshold = new double[26];
        Arrays.fill(gain, -1);
        Arrays.fill(threshold, 30);
        OfflineRestorationProfile p =
            new OfflineRestorationProfile(SR, 2, 1, 1, .5, gain, threshold);
        gain[0] = -300;
        threshold[0] = -900;
        double[] exposed = p.getStaticGainDb();
        exposed[0] = -100;
        assertEquals(-1, p.getStaticGainDb()[0], 0);
        assertEquals(30, p.getDynamicThresholdDb()[0], 0);
        assertThrows(IllegalArgumentException.class, () -> new OfflineRestorationDsp(48000, 2, p));
        assertThrows(IllegalArgumentException.class, () -> new OfflineRestorationDsp(SR, 1, p));
    }

    @Test
    public void analysisIsChunkIndependentAndDoesNotFlattenBroadbandNoise() {
        short[] samples = new short[SR * 6];
        Random random = new Random(481);
        for (int i = 0; i < samples.length; i++)
            samples[i] = (short) (random.nextInt(16001) - 8000);
        OfflineRestorationProfile one = analyze(samples, 1);
        OfflineRestorationProfile.Analyzer a = new OfflineRestorationProfile.Analyzer(SR, 1);
        for (int i = 0; i < samples.length; i += 109) {
            short[] block = Arrays.copyOfRange(samples, i, Math.min(i + 109, samples.length));
            a.accept(block, block.length);
        }
        OfflineRestorationProfile many = a.finish();
        assertArrayEquals(one.getStaticGainDb(), many.getStaticGainDb(), 0);
        assertTrue("Broadband crowd/noise is not a resonance", one.passthrough);
    }

    @Test
    public void noAddedClippingOnFullScaleAlternatingAndImpulseInputs() throws Exception {
        short[] samples = new short[SR * 2];
        for (int n = 0; n < samples.length; n++) samples[n] = (short) (n % 2 == 0 ? 32767 : -32768);
        OfflineRestorationProfile p = new OfflineRestorationProfile(SR, 1, samples.length, 1, 1,
            profile(1, -3).getStaticGainDb(), profile(1, -3).getDynamicThresholdDb());
        render(samples, 1, p, 151);
        Arrays.fill(samples, (short) 0);
        samples[0] = 32767;
        samples[2047] = -32768;
        samples[samples.length - 1] = 32767;
        render(samples, 1, p, 151);
    }

    @Test
    public void broadResonanceEstimateStableAcrossSampleRates() {
        double minimum = 10, maximum = 0;
        for (int sr : new int[] {44100, 48000, 96000}) {
            short[] colored = new short[sr * 8];
            Random random = new Random(642);
            double a = Math.pow(10, 15 / 40.0), w = 2 * Math.PI * 315 / sr,
                   alpha = Math.sin(w) / (2 * 1.2);
            double a0 = 1 + alpha / a, b0 = (1 + alpha * a) / a0, b1 = -2 * Math.cos(w) / a0,
                   b2 = (1 - alpha * a) / a0;
            double a1 = b1, a2 = (1 - alpha / a) / a0, z1 = 0, z2 = 0;
            for (int n = 0; n < colored.length; n++) {
                double x = (random.nextDouble() - .5) * .25, y = b0 * x + z1;
                z1 = b1 * x - a1 * y + z2;
                z2 = b2 * x - a2 * y;
                colored[n] = (short) Math.round(y * 32768);
            }
            OfflineRestorationProfile.Analyzer analyzer =
                new OfflineRestorationProfile.Analyzer(sr, 1);
            analyzer.accept(colored, colored.length);
            OfflineRestorationProfile p = analyzer.finish();
            assertFalse("Injected broad resonance should be detected at " + sr, p.passthrough);
            minimum = Math.min(minimum, p.maximumCorrectionDb);
            maximum = Math.max(maximum, p.maximumCorrectionDb);
            assertTrue(
                "Never saturate correction from missing FFT bins", p.maximumCorrectionDb < 2.5);
        }
        assertTrue("Same coloration should yield similar correction at all sample rates",
            maximum - minimum < .75);
    }

    private static OfflineRestorationProfile analyze(short[] samples, int channels) {
        OfflineRestorationProfile.Analyzer a = new OfflineRestorationProfile.Analyzer(SR, channels);
        a.accept(samples, samples.length);
        return a.finish();
    }
    private static OfflineRestorationProfile profile(int channels, double gainDb) {
        double[] gain = new double[26], threshold = new double[26];
        Arrays.fill(gain, gainDb);
        Arrays.fill(threshold, 30);
        return new OfflineRestorationProfile(SR, channels, 1, 1, .5, gain, threshold);
    }
    private static OfflineRestorationProfile shapedProfile() {
        double[] gain = new double[26], threshold = new double[26];
        Arrays.fill(threshold, -30);
        for (int i = 3; i < 14; i++) gain[i] = -2.5 * Math.pow(Math.sin((i - 3) * Math.PI / 11), 2);
        return new OfflineRestorationProfile(SR, 2, 1, 1, .5, gain, threshold);
    }
    private static short[] complexStereo(int frames) {
        short[] result = new short[frames * 2];
        Random random = new Random(918);
        for (int n = 0; n < frames; n++) {
            result[2 * n] =
                (short) (7000 * Math.sin(2 * Math.PI * 310 * n / SR) + random.nextInt(3001) - 1500);
            result[2 * n + 1] =
                (short) (5000 * Math.sin(2 * Math.PI * 403 * n / SR) + random.nextInt(3001) - 1500);
        }
        return result;
    }
    private static short[] render(short[] samples, int channels, OfflineRestorationProfile p,
        int chunkFrames) throws IOException {
        OfflineRestorationDsp dsp = new OfflineRestorationDsp(SR, channels, p);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OfflineRestorationDsp.PcmSink sink = (data, count) -> {
            for (int i = 0; i < count; i++) {
                bytes.write(data[i] & 255);
                bytes.write((data[i] >> 8) & 255);
            }
        };
        for (int i = 0; i < samples.length; i += chunkFrames * channels) {
            short[] block = Arrays.copyOfRange(
                samples, i, Math.min(i + chunkFrames * channels, samples.length));
            dsp.process(block, block.length, sink);
        }
        dsp.finish(sink);
        assertEquals(samples.length / channels, dsp.getOutputFrames());
        assertEquals(0, dsp.getClippedSampleCount());
        byte[] raw = bytes.toByteArray();
        short[] out = new short[raw.length / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (short) ((raw[2 * i] & 255) | (raw[2 * i + 1] << 8));
        return out;
    }
}
