package com.bayapps.android.robophish.audio.offline;

import java.util.Arrays;
import java.util.Locale;

/**
 * Immutable, content-derived parameters for the separate offline render.
 * This is conservative adaptive mastering, not recovery of a soundboard source.
 */
public final class OfflineRestorationProfile {
    public static final String VERSION = "offline-v1";
    static int fftSizeFor(int sampleRate) {
        return sampleRate > 48000 ? 4096 : sampleRate < 32000 ? 1024 : 2048;
    }
    static final double[] CENTERS = {50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800,
        1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000};
    public final int sampleRate, channels, analysisFftSize;
    public final String algorithmVersion = VERSION;
    public final String summary;
    public final long analyzedFrames;
    public final int analyzedWindows;
    public final double sourcePeak, outputGain, maximumCorrectionDb;
    public final boolean passthrough;
    private final double[] staticGainDb, dynamicThresholdDb;

    OfflineRestorationProfile(int sr, int channels, long frames, int windows, double peak,
        double[] gain, double[] threshold) {
        validateFormat(sr, channels);
        if (gain.length != CENTERS.length || threshold.length != CENTERS.length) {
            throw new IllegalArgumentException("Invalid band profile");
        }
        this.sampleRate = sr;
        this.channels = channels;
        this.analysisFftSize = fftSizeFor(sr);
        this.analyzedFrames = frames;
        this.analyzedWindows = windows;
        this.sourcePeak = peak;
        this.staticGainDb = gain.clone();
        this.dynamicThresholdDb = threshold.clone();
        double max = 0;
        for (int i = 0; i < gain.length; i++) {
            if (!Double.isFinite(gain[i]) || gain[i] < -3.000001 || gain[i] > 0
                || !Double.isFinite(threshold[i]))
                throw new IllegalArgumentException("Unsafe band profile");
            max = Math.max(max, -gain[i]);
        }
        maximumCorrectionDb = max;
        passthrough = max < 0.25;
        // Reserve sample headroom without compressing the whole track's dynamics.
        // A separate output clipping guard still rejects any exceptional overshoot.
        outputGain = passthrough ? 1 : Math.min(1, 0.80 / Math.max(peak, 1e-12));
        summary = passthrough
            ? "No clear correction found. This copy keeps the original audio unchanged."
            : String.format(Locale.US,
                "Adjusted low and middle frequencies by up to %.1f dB, with gentle control of sustained buildup. No generated audio.",
                max);
    }

    public double[] getBandCentersHz() {
        return CENTERS.clone();
    }
    public double[] getStaticGainDb() {
        return staticGainDb.clone();
    }
    public double[] getDynamicThresholdDb() {
        return dynamicThresholdDb.clone();
    }
    double gainAt(int band) {
        return staticGainDb[band];
    }
    double thresholdAt(int band) {
        return dynamicThresholdDb[band];
    }
    static void validateFormat(int sr, int channels) {
        if (sr < 22050 || sr > 96000 || channels < 1 || channels > 2)
            throw new IllegalArgumentException(
                "Offline processing supports mono/stereo PCM at 22050–96000 Hz");
    }
    static int bandOf(double hz) {
        if (hz <= CENTERS[0])
            return 0;
        for (int b = 0; b < CENTERS.length - 1; b++)
            if (hz < Math.sqrt(CENTERS[b] * CENTERS[b + 1]))
                return b;
        return CENTERS.length - 1;
    }

    /** Constant-memory full-track analysis. No source file or network IO. */
    public static final class Analyzer {
        private final int sampleRate, channels, fftSize;
        private final double[][] samples, real, imag;
        private final double[] logDensity = new double[CENTERS.length];
        private final int[][] relativeHistogram = new int[CENTERS.length][241];
        private final int[][] curvatureHistogram = new int[CENTERS.length][241];
        private final int[] counts = new int[CENTERS.length];
        private final double[] window;
        private int filled, windows;
        private long totalFrames;
        private double peak;
        private boolean finished;

        public Analyzer(int sampleRate, int channels) {
            validateFormat(sampleRate, channels);
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.fftSize = fftSizeFor(sampleRate);
            this.window = new double[fftSize];
            samples = new double[channels][fftSize];
            real = new double[channels][fftSize];
            imag = new double[channels][fftSize];
            for (int n = 0; n < fftSize; n++) window[n] = Math.sin(Math.PI * (n + 0.5) / fftSize);
            for (int k = 1; k <= fftSize / 2; k++)
                counts[bandOf((double) k * sampleRate / fftSize)]++;
        }

        public void accept(short[] input, int sampleCount) {
            if (finished)
                throw new IllegalStateException("Analyzer already finished");
            validateInput(input, sampleCount, channels);
            for (int offset = 0; offset < sampleCount; offset += channels) {
                for (int c = 0; c < channels; c++) {
                    double value = input[offset + c] / 32768.0;
                    samples[c][filled] = value;
                    peak = Math.max(peak, Math.abs(value));
                }
                filled++;
                totalFrames++;
                if (filled == fftSize) {
                    analyze();
                    filled = 0;
                }
            }
        }

        public OfflineRestorationProfile finish() {
            if (finished)
                throw new IllegalStateException("Analyzer already finished");
            finished = true;
            if (totalFrames == 0)
                throw new IllegalArgumentException("Empty source audio");
            // A short last window has a biased spectrum; use it only for short files.
            if (windows == 0 && filled > 0) {
                for (int c = 0; c < channels; c++) Arrays.fill(samples[c], filled, fftSize, 0);
                analyze();
            }
            double[] gain = new double[CENTERS.length], threshold = new double[CENTERS.length];
            Arrays.fill(threshold, 30);
            if (windows > 0) {
                double[] density = new double[CENTERS.length];
                double maximum = -300;
                for (int b = 0; b < CENTERS.length; b++) {
                    density[b] = logDensity[b] / windows;
                    if (counts[b] > 0)
                        maximum = Math.max(maximum, density[b]);
                    threshold[b] = quantile(relativeHistogram[b], 0.80) + 1.5;
                }
                int populated = 0;
                for (int b = 0; b < CENTERS.length; b++)
                    if (counts[b] > 0 && density[b] > maximum - 30)
                        populated++;
                // Pure tones, silence, and tiny excerpts provide no reliable room profile.
                if (populated >= 10 && totalFrames >= sampleRate * 3L) {
                    for (int b = 3; b < CENTERS.length - 3; b++) {
                        // Never use an unrepresented low-frequency band as a -200 dB
                        // baseline (particularly important at 88.2/96 kHz).
                        if (CENTERS[b] > 1000 || counts[b] == 0 || counts[b - 3] == 0
                            || counts[b + 3] == 0)
                            continue;
                        // A peak must persist in at least 75% of active analysis windows.
                        // The initial mean-only estimator mistook musical coloration for
                        // a defect in controlled trials, particularly on mild degradations.
                        double excess = quantile(curvatureHistogram[b], 0.25, -60);
                        // Only persistent broad excess; never boost missing treble or notch notes.
                        gain[b] = -Math.min(3, Math.max(0, (excess - 3.0) * 0.50));
                    }
                    double[] smooth = gain.clone();
                    for (int b = 1; b < gain.length - 1; b++)
                        smooth[b] = (gain[b - 1] + 2 * gain[b] + gain[b + 1]) / 4;
                    gain = smooth;
                }
            }
            return new OfflineRestorationProfile(
                sampleRate, channels, totalFrames, windows, peak, gain, threshold);
        }

        private void analyze() {
            double[] bandPower = new double[CENTERS.length];
            double total = 0;
            for (int c = 0; c < channels; c++) {
                for (int n = 0; n < fftSize; n++) {
                    real[c][n] = samples[c][n] * window[n];
                    imag[c][n] = 0;
                }
                OfflineRestorationDsp.fft(real[c], imag[c], false);
                for (int k = 1; k <= fftSize / 2; k++) {
                    double p = (real[c][k] * real[c][k] + imag[c][k] * imag[c][k]) / channels;
                    bandPower[bandOf((double) k * sampleRate / fftSize)] += p;
                    total += p;
                }
            }
            // Ignore near-silence; it must not dictate the music's tonal balance.
            if (total / (fftSize * (double) fftSize) < 1e-8)
                return;
            windows++;
            double reference = total / (fftSize / 2.0);
            double[] densities = new double[CENTERS.length];
            for (int b = 0; b < CENTERS.length; b++) {
                double density = bandPower[b] / Math.max(1, counts[b]);
                densities[b] = 10 * Math.log10(Math.max(density, 1e-20));
                logDensity[b] += densities[b];
                double relative = 10 * Math.log10(Math.max(density / reference, 1e-12));
                int bin = (int) Math.round((relative + 90) * 2);
                relativeHistogram[b][Math.max(0, Math.min(240, bin))]++;
            }
            for (int b = 3; b < CENTERS.length - 3; b++)
                if (counts[b] > 0 && counts[b - 3] > 0 && counts[b + 3] > 0) {
                    double fraction = Math.log(CENTERS[b] / CENTERS[b - 3])
                        / Math.log(CENTERS[b + 3] / CENTERS[b - 3]);
                    double baseline =
                        densities[b - 3] + fraction * (densities[b + 3] - densities[b - 3]);
                    int bin = (int) Math.round((densities[b] - baseline + 60) * 2);
                    curvatureHistogram[b][Math.max(0, Math.min(240, bin))]++;
                }
        }
        private static double quantile(int[] histogram, double fraction) {
            return quantile(histogram, fraction, -90);
        }
        private static double quantile(int[] histogram, double fraction, double lowestDb) {
            long total = 0;
            for (int value : histogram) total += value;
            long target = (long) Math.ceil(total * fraction), sum = 0;
            if (total == 0)
                return 30;
            for (int i = 0; i < histogram.length; i++) {
                sum += histogram[i];
                if (sum >= target)
                    return i / 2.0 + lowestDb;
            }
            return 30;
        }
    }
    static void validateInput(short[] input, int samples, int channels) {
        if (input == null || samples < 0 || samples > input.length || samples % channels != 0)
            throw new IllegalArgumentException("Expected complete interleaved PCM frames");
    }
}
