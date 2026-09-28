package com.log_to_kot.maniacmod.voice.dsp;

/*
 * Портовано з sv-voice-changer (https://github.com/sawiq_). Copyright (c) 2026 sawiq_.
 * Ліцензія: CC BY-NC-SA 4.0 — повний текст і умови в voice/dsp/LICENSE-DSP.txt.
 */

/**
 * Direct-form-I biquad section using Robert Bristow-Johnson's Audio EQ
 * Cookbook coefficient formulas.
 *
 * <p>One instance filters one channel; the state is the filter's memory, so
 * instances must not be shared between channels or streams.</p>
 */
public final class Biquad {
    private static final double SQRT_2 = Math.sqrt(2.0D);

    private double b0 = 1.0D;
    private double b1;
    private double b2;
    private double a1;
    private double a2;

    private double x1;
    private double x2;
    private double y1;
    private double y2;

    /** Default Q for a shelving filter with no resonant bump. */
    public static final double SHELF_SLOPE_Q = 1.0D / SQRT_2;

    public void setLowShelf(double sampleRate, double frequency, double gainDb) {
        if (isBypass(gainDb)) {
            setBypass();
            return;
        }

        double a = Math.pow(10.0D, gainDb / 40.0D);
        double w0 = angularFrequency(sampleRate, frequency);
        double cosW0 = Math.cos(w0);
        double alpha = Math.sin(w0) / 2.0D * Math.sqrt((a + 1.0D / a) * (1.0D / SHELF_SLOPE_Q - 1.0D) + 2.0D);
        double twoSqrtAAlpha = 2.0D * Math.sqrt(a) * alpha;

        double b0n = a * ((a + 1.0D) - (a - 1.0D) * cosW0 + twoSqrtAAlpha);
        double b1n = 2.0D * a * ((a - 1.0D) - (a + 1.0D) * cosW0);
        double b2n = a * ((a + 1.0D) - (a - 1.0D) * cosW0 - twoSqrtAAlpha);
        double a0n = (a + 1.0D) + (a - 1.0D) * cosW0 + twoSqrtAAlpha;
        double a1n = -2.0D * ((a - 1.0D) + (a + 1.0D) * cosW0);
        double a2n = (a + 1.0D) + (a - 1.0D) * cosW0 - twoSqrtAAlpha;

        normalize(b0n, b1n, b2n, a0n, a1n, a2n);
    }

    public void setHighShelf(double sampleRate, double frequency, double gainDb) {
        if (isBypass(gainDb)) {
            setBypass();
            return;
        }

        double a = Math.pow(10.0D, gainDb / 40.0D);
        double w0 = angularFrequency(sampleRate, frequency);
        double cosW0 = Math.cos(w0);
        double alpha = Math.sin(w0) / 2.0D * Math.sqrt((a + 1.0D / a) * (1.0D / SHELF_SLOPE_Q - 1.0D) + 2.0D);
        double twoSqrtAAlpha = 2.0D * Math.sqrt(a) * alpha;

        double b0n = a * ((a + 1.0D) + (a - 1.0D) * cosW0 + twoSqrtAAlpha);
        double b1n = -2.0D * a * ((a - 1.0D) + (a + 1.0D) * cosW0);
        double b2n = a * ((a + 1.0D) + (a - 1.0D) * cosW0 - twoSqrtAAlpha);
        double a0n = (a + 1.0D) - (a - 1.0D) * cosW0 + twoSqrtAAlpha;
        double a1n = 2.0D * ((a - 1.0D) - (a + 1.0D) * cosW0);
        double a2n = (a + 1.0D) - (a - 1.0D) * cosW0 - twoSqrtAAlpha;

        normalize(b0n, b1n, b2n, a0n, a1n, a2n);
    }

    public void setPeaking(double sampleRate, double frequency, double q, double gainDb) {
        if (isBypass(gainDb)) {
            setBypass();
            return;
        }

        double a = Math.pow(10.0D, gainDb / 40.0D);
        double w0 = angularFrequency(sampleRate, frequency);
        double cosW0 = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0D * q);

        normalize(
                1.0D + alpha * a,
                -2.0D * cosW0,
                1.0D - alpha * a,
                1.0D + alpha / a,
                -2.0D * cosW0,
                1.0D - alpha / a
        );
    }

    public void setHighPass(double sampleRate, double frequency, double q) {
        double w0 = angularFrequency(sampleRate, frequency);
        double cosW0 = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0D * q);
        double onePlusCos = 1.0D + cosW0;

        normalize(
                onePlusCos / 2.0D,
                -onePlusCos,
                onePlusCos / 2.0D,
                1.0D + alpha,
                -2.0D * cosW0,
                1.0D - alpha
        );
    }

    public void setLowPass(double sampleRate, double frequency, double q) {
        double w0 = angularFrequency(sampleRate, frequency);
        double cosW0 = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0D * q);
        double oneMinusCos = 1.0D - cosW0;

        normalize(
                oneMinusCos / 2.0D,
                oneMinusCos,
                oneMinusCos / 2.0D,
                1.0D + alpha,
                -2.0D * cosW0,
                1.0D - alpha
        );
    }

    /** Unity-gain passthrough. */
    public void setBypass() {
        this.b0 = 1.0D;
        this.b1 = 0.0D;
        this.b2 = 0.0D;
        this.a1 = 0.0D;
        this.a2 = 0.0D;
    }

    public double process(double input) {
        double output = this.b0 * input + this.b1 * this.x1 + this.b2 * this.x2
                - this.a1 * this.y1 - this.a2 * this.y2;

        this.x2 = this.x1;
        this.x1 = input;
        this.y2 = this.y1;
        this.y1 = output;

        // Direct form I on a silent input can park on denormals, which cost
        // orders of magnitude more than normal arithmetic on x86.
        if (Math.abs(output) < 1.0e-20D) {
            this.y1 = 0.0D;
            return 0.0D;
        }

        return output;
    }

    /** Clears the filter memory. Call when a stream restarts to avoid clicks. */
    public void reset() {
        this.x1 = 0.0D;
        this.x2 = 0.0D;
        this.y1 = 0.0D;
        this.y2 = 0.0D;
    }

    private static boolean isBypass(double gainDb) {
        return Math.abs(gainDb) < 0.01D;
    }

    private static double angularFrequency(double sampleRate, double frequency) {
        double nyquistLimited = Math.max(10.0D, Math.min(sampleRate * 0.45D, frequency));
        return 2.0D * Math.PI * nyquistLimited / sampleRate;
    }

    private void normalize(double b0n, double b1n, double b2n, double a0n, double a1n, double a2n) {
        this.b0 = b0n / a0n;
        this.b1 = b1n / a0n;
        this.b2 = b2n / a0n;
        this.a1 = a1n / a0n;
        this.a2 = a2n / a0n;
    }
}
