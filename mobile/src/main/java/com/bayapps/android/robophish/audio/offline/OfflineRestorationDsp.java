package com.bayapps.android.robophish.audio.offline;

import java.io.IOException;
import java.util.Arrays;

/**
 * Two-pass offline, channel-linked spectral dynamics. Does not suppress a noise
 * floor, remove crowd, synthesize content, or change interchannel spectral phase.
 * A WOLA overlap/add path provides smooth time-varying gain and exact duration.
 */
public final class OfflineRestorationDsp {
    public static final String VERSION = OfflineRestorationProfile.VERSION;
    private static final int B = OfflineRestorationProfile.CENTERS.length;
    private final int N, H;
    @FunctionalInterface
    public interface PcmSink {
        void write(short[] interleaved, int sampleCount) throws IOException;
    }
    private final int sampleRate, channels;
    private final OfflineRestorationProfile profile;
    private final double[][] input, real, imag, ola;
    private final double[] normalization, window, previousPower;
    private final double[] dynamicDb = new double[B], staticLinear;
    private final double[] power, bands = new double[B];
    private final int[] binBand, bandCounts = new int[B];
    private final short[] output;
    private int filled, discard;
    private long inputFrames, outputFrames, clippedSamples, processedWindows, dynamicWindows;
    private double largestDynamicReductionDb, outputPeak;
    private boolean finished;

    public OfflineRestorationDsp(int sampleRate, int channels, OfflineRestorationProfile profile) {
        OfflineRestorationProfile.validateFormat(sampleRate, channels);
        if (profile == null || profile.sampleRate != sampleRate || profile.channels != channels)
            throw new IllegalArgumentException("Profile/source format mismatch");
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.profile = profile;
        N = OfflineRestorationProfile.fftSizeFor(sampleRate);
        H = N / 4;
        // Start with N-H zero frames. Each transform emits one H-frame hop;
        // discarding the same N-H frames aligns output sample zero with input zero.
        filled = N - H;
        discard = N - H;
        normalization = new double[N];
        window = new double[N];
        previousPower = new double[N / 2 + 1];
        staticLinear = new double[N / 2 + 1];
        power = new double[N / 2 + 1];
        binBand = new int[N / 2 + 1];
        input = new double[channels][N];
        real = new double[channels][N];
        imag = new double[channels][N];
        ola = new double[channels][N];
        output = new short[H * channels];
        for (int n = 0; n < N; n++) window[n] = Math.sin(Math.PI * (n + 0.5) / N);
        for (int k = 0; k <= N / 2; k++) {
            double hz = (double) k * sampleRate / N;
            binBand[k] = OfflineRestorationProfile.bandOf(hz);
            bandCounts[binBand[k]]++;
            staticLinear[k] = Math.pow(10, interpolatedGain(hz) / 20);
        }
    }

    public void process(short[] samples, int sampleCount, PcmSink sink) throws IOException {
        if (finished)
            throw new IllegalStateException("Processor already finished");
        OfflineRestorationProfile.validateInput(samples, sampleCount, channels);
        if (profile.passthrough) {
            for (int i = 0; i < sampleCount; i++)
                outputPeak = Math.max(outputPeak, Math.abs(samples[i] / 32768.0));
            sink.write(samples, sampleCount);
            inputFrames += sampleCount / channels;
            outputFrames += sampleCount / channels;
            return;
        }
        for (int offset = 0; offset < sampleCount; offset += channels) {
            for (int c = 0; c < channels; c++) input[c][filled] = samples[offset + c] / 32768.0;
            filled++;
            inputFrames++;
            if (filled == N)
                renderWindow(sink, false);
        }
    }

    public void finish(PcmSink sink) throws IOException {
        if (finished)
            throw new IllegalStateException("Processor already finished");
        finished = true;
        // Zero padding drains the overlap buffer. renderWindow caps writes at the
        // number of real input frames, so padding never extends the saved song.
        if (!profile.passthrough)
            while (outputFrames < inputFrames) {
                for (int c = 0; c < channels; c++) Arrays.fill(input[c], filled, N, 0);
                filled = N;
                renderWindow(sink, true);
            }
        if (inputFrames != outputFrames)
            throw new IllegalStateException("Output duration mismatch");
    }
    public long getInputFrames() {
        return inputFrames;
    }
    public long getOutputFrames() {
        return outputFrames;
    }
    public long getClippedSampleCount() {
        return clippedSamples;
    }
    public long getProcessedWindows() {
        return processedWindows;
    }
    public long getDynamicWindows() {
        return dynamicWindows;
    }
    public double getLargestDynamicReductionDb() {
        return largestDynamicReductionDb;
    }
    public double getOutputPeak() {
        return outputPeak;
    }

    private void renderWindow(PcmSink sink, boolean flushing) throws IOException {
        Arrays.fill(power, 0);
        Arrays.fill(bands, 0);
        double total = 0, positiveFlux = 0, logPower = 0, flatPower = 0;
        int flatCount = 0;
        for (int c = 0; c < channels; c++) {
            for (int n = 0; n < N; n++) {
                real[c][n] = input[c][n] * window[n];
                imag[c][n] = 0;
            }
            fft(real[c], imag[c], false);
            for (int k = 0; k <= N / 2; k++)
                power[k] += (real[c][k] * real[c][k] + imag[c][k] * imag[c][k]) / channels;
        }
        for (int k = 1; k <= N / 2; k++) {
            total += power[k];
            bands[binBand[k]] += power[k];
            positiveFlux += Math.max(0, power[k] - previousPower[k]);
            previousPower[k] = power[k];
            double hz = (double) k * sampleRate / N;
            if (hz >= 100 && hz <= 8000) {
                logPower += Math.log(Math.max(1e-20, power[k]));
                flatPower += power[k];
                flatCount++;
            }
        }
        double novelty = positiveFlux / Math.max(total, 1e-20);
        double flatness = Math.exp(logPower / Math.max(1, flatCount))
            / Math.max(1e-20, flatPower / Math.max(1, flatCount));
        // Abrupt musical/crowd onsets, quiet passages and broadband ambience are protected.
        double protection = Math.min(1, Math.max(0, (0.35 - novelty) / 0.25));
        protection *= Math.min(1, Math.max(0, (0.30 - flatness) / 0.15));
        if (total / (N * (double) N) < 1e-7 || flushing)
            protection = 0;
        double reference = total / (N / 2.0), maximum = 0;
        for (int b = 0; b < B; b++) {
            double relative = 10
                * Math.log10(Math.max(
                    1e-12, bands[b] / Math.max(1, bandCounts[b]) / Math.max(reference, 1e-20)));
            double confidence = Math.min(1, Math.max(0, (-profile.gainAt(b) - 0.25) / 1.0));
            double wanted = Math.min(1.5, Math.max(0, (relative - profile.thresholdAt(b)) * 0.5))
                * confidence * protection;
            // Fast release of attenuation protects the next onset; slower engagement avoids
            // pumping.
            double seconds = wanted < dynamicDb[b] ? 0.008 : 0.120;
            double smoothing = Math.exp(-H / (sampleRate * seconds));
            dynamicDb[b] = smoothing * dynamicDb[b] + (1 - smoothing) * wanted;
            maximum = Math.max(maximum, dynamicDb[b]);
        }
        processedWindows++;
        if (maximum > 0.05)
            dynamicWindows++;
        largestDynamicReductionDb = Math.max(largestDynamicReductionDb, maximum);
        for (int k = 0; k <= N / 2; k++) {
            int band = binBand[k];
            double attenuation = (dynamicDb[Math.max(0, band - 1)] + 2 * dynamicDb[band]
                                     + dynamicDb[Math.min(B - 1, band + 1)])
                / 4;
            double gain = staticLinear[k] * Math.pow(10, -attenuation / 20);
            // Exactly the same real-valued mask on both channels preserves phase relationships.
            for (int c = 0; c < channels; c++) {
                real[c][k] *= gain;
                imag[c][k] *= gain;
                if (k > 0 && k < N / 2) {
                    real[c][N - k] = real[c][k];
                    imag[c][N - k] = -imag[c][k];
                }
            }
        }
        for (int c = 0; c < channels; c++) {
            fft(real[c], imag[c], true);
            for (int n = 0; n < N; n++) ola[c][n] += real[c][n] * window[n];
        }
        // Both analysis and synthesis apply the sine window. Divide by the sum
        // of their squared windows, including partial overlap at the boundaries.
        // A unity spectral mask therefore reconstructs every aligned sample.
        for (int n = 0; n < N; n++) normalization[n] += window[n] * window[n];
        int written = 0;
        for (int n = 0; n < H; n++) {
            if (discard > 0) {
                discard--;
                continue;
            }
            if (outputFrames >= inputFrames)
                break;
            for (int c = 0; c < channels; c++) {
                double value = ola[c][n] / Math.max(1e-20, normalization[n]) * profile.outputGain;
                outputPeak = Math.max(outputPeak, Math.abs(value));
                long sample = Math.round(value * 32768);
                if (sample > 32767 || sample < -32768)
                    clippedSamples++;
                output[written++] = (short) Math.max(-32768, Math.min(32767, sample));
            }
            outputFrames++;
        }
        if (written > 0)
            sink.write(output, written);
        for (int c = 0; c < channels; c++) {
            System.arraycopy(input[c], H, input[c], 0, N - H);
            Arrays.fill(input[c], N - H, N, 0);
            System.arraycopy(ola[c], H, ola[c], 0, N - H);
            Arrays.fill(ola[c], N - H, N, 0);
        }
        System.arraycopy(normalization, H, normalization, 0, N - H);
        Arrays.fill(normalization, N - H, N, 0);
        filled = N - H;
    }

    private double interpolatedGain(double hz) {
        if (hz <= OfflineRestorationProfile.CENTERS[0])
            return profile.gainAt(0);
        for (int b = 0; b < B - 1; b++)
            if (hz < OfflineRestorationProfile.CENTERS[b + 1]) {
                double f = Math.log(hz / OfflineRestorationProfile.CENTERS[b])
                    / Math.log(OfflineRestorationProfile.CENTERS[b + 1]
                        / OfflineRestorationProfile.CENTERS[b]);
                return profile.gainAt(b) + f * (profile.gainAt(b + 1) - profile.gainAt(b));
            }
        return profile.gainAt(B - 1);
    }

    /**
     * In-place radix-2 FFT. The forward transform is unscaled; the inverse divides
     * by N. WOLA normalization is performed separately after synthesis windowing.
     * No platform dependencies or native libraries.
     */
    static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = (inverse ? 2 : -2) * Math.PI / len, wRe = Math.cos(angle),
                   wIm = Math.sin(angle);
            for (int offset = 0; offset < n; offset += len) {
                double wr = 1, wi = 0;
                for (int j = 0; j < len / 2; j++) {
                    int a = offset + j, b = a + len / 2;
                    double tr = wr * re[b] - wi * im[b], ti = wr * im[b] + wi * re[b];
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    double next = wr * wRe - wi * wIm;
                    wi = wr * wIm + wi * wRe;
                    wr = next;
                }
            }
        }
        if (inverse)
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
    }
}
