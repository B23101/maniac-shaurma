package com.log_to_kot.maniacmod.voice.dsp;

/*
 * Портовано з sv-voice-changer (https://github.com/sawiq_). Copyright (c) 2026 sawiq_.
 * Ліцензія: CC BY-NC-SA 4.0 — повний текст і умови в voice/dsp/LICENSE-DSP.txt.
 */

/**
 * Feedback peak limiter with a saturating safety stage.
 *
 * <p>The heavy presets stack a sub-octave layer, an EQ boost and drive on top
 * of one another and comfortably exceed full scale. Clamping that to plus or
 * minus one squares off the waveform and is heard as crackle; riding the gain
 * down instead keeps the tone and only costs a little loudness.</p>
 */
public final class SoftLimiter {
    private static final double CEILING = 0.94D;
    private static final double RELEASE_SECONDS = 0.150D;

    private final double releaseCoefficient;
    private double gain = 1.0D;

    public SoftLimiter(double sampleRate) {
        this.releaseCoefficient = 1.0D - Math.exp(-1.0D / (sampleRate * RELEASE_SECONDS));
    }

    public double process(double input) {
        double magnitude = Math.abs(input) * this.gain;
        if (magnitude > CEILING) {
            // Instant attack: a limiter that lets the first sample of a
            // transient through has not limited anything.
            this.gain *= CEILING / magnitude;
        } else {
            this.gain += this.releaseCoefficient * (1.0D - this.gain);
        }

        double limited = input * this.gain;
        return Math.abs(limited) > CEILING ? Math.tanh(limited) : limited;
    }

    public void reset() {
        this.gain = 1.0D;
    }
}
