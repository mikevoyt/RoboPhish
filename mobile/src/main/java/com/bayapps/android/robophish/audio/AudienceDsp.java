package com.bayapps.android.robophish.audio;

/**
 * Causal PCM processing. No lookahead, buffering, file IO, locks, or model inference.
 * Off is bit-exact after a 50 ms crossfade. Same filters and limiter gain on all
 * channels preserve channel relationships. This is tonal correction, not source recovery.
 */
public final class AudienceDsp {
    public enum Mode { RUMBLE, CLARITY }
    private final int channels;
    private final Biquad[] highpass, mud;
    private final double[] clarityFrame;
    private final double rampStep, release;
    private double wet = 0, clarity = 0, limiter = 1;

    public AudienceDsp(int sampleRate, int channels) {
        if (sampleRate < 8000 || sampleRate > 192000 || channels < 1 || channels > 8) {
            throw new IllegalArgumentException("Unsupported PCM format");
        }
        this.channels = channels;
        rampStep = 1.0 / (sampleRate * 0.05);
        release = Math.exp(-1.0 / (sampleRate * 0.080));
        highpass = new Biquad[channels]; mud = new Biquad[channels];
        clarityFrame = new double[channels];
        for (int c = 0; c < channels; c++) {
            highpass[c] = Biquad.highpass(sampleRate, 30, Math.sqrt(0.5));
            mud[c] = Biquad.bell(sampleRate, 280, -1.5, 0.7);
        }
    }

    /** Input/output may be the same array; samples must contain whole interleaved frames. */
    public void process(short[] input, short[] output, int samples, boolean enabled, float amount, Mode mode) {
        if (samples < 0 || samples > input.length || samples > output.length || samples % channels != 0) {
            throw new IllegalArgumentException("Expected complete PCM frames");
        }
        double target = enabled && Float.isFinite(amount) ? Math.max(0, Math.min(1, amount)) : 0;
        double targetClarity = mode == Mode.CLARITY ? 1 : 0;
        for (int offset = 0; offset < samples; offset += channels) {
            wet = approach(wet, target, rampStep);
            clarity = approach(clarity, targetClarity, rampStep);
            double peak = 0;
            for (int c = 0; c < channels; c++) {
                double hp = highpass[c].process(input[offset + c] / 32768.0);
                // Subtractive EQ only: boosting presence failed corpus stereo guardrails.
                clarityFrame[c] = mud[c].process(hp);
                double processed = hp + clarity * (clarityFrame[c] - hp);
                clarityFrame[c] = processed;
                peak = Math.max(peak, Math.abs(processed));
            }
            double needed = peak > 0.95 ? 0.95 / peak : 1;
            limiter = needed < limiter ? needed : release * limiter + (1 - release) * needed;
            for (int c = 0; c < channels; c++) {
                if (wet == 0) {
                    output[offset + c] = input[offset + c];
                } else {
                    double original = input[offset + c] / 32768.0;
                    double result = original + wet * (clarityFrame[c] * limiter - original);
                    output[offset + c] = (short) Math.max(-32768, Math.min(32767, Math.round(result * 32768)));
                }
            }
        }
    }

    private static double approach(double value, double target, double step) {
        return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
    }

    /** RBJ bilinear-transform equations; transposed direct form II. */
    private static final class Biquad {
        final double b0,b1,b2,a1,a2;
        double z1,z2;
        Biquad(double b0,double b1,double b2,double a0,double a1,double a2) {
            this.b0=b0/a0;this.b1=b1/a0;this.b2=b2/a0;this.a1=a1/a0;this.a2=a2/a0;
        }
        double process(double x) {
            double y=b0*x+z1;
            z1=b1*x-a1*y+z2;z2=b2*x-a2*y;
            // Bound denormal work without changing audible samples.
            if (Math.abs(z1)<1e-30) z1=0;
            if (Math.abs(z2)<1e-30) z2=0;
            return y;
        }
        static Biquad highpass(int sr,double frequency,double q) {
            double w=2*Math.PI*frequency/sr,c=Math.cos(w),alpha=Math.sin(w)/(2*q);
            return new Biquad((1+c)/2,-(1+c),(1+c)/2,1+alpha,-2*c,1-alpha);
        }
        static Biquad bell(int sr,double frequency,double gainDb,double q) {
            double a=Math.pow(10,gainDb/40),w=2*Math.PI*frequency/sr,c=Math.cos(w),alpha=Math.sin(w)/(2*q);
            return new Biquad(1+alpha*a,-2*c,1-alpha*a,1+alpha/a,-2*c,1-alpha/a);
        }
    }
}
