package com.log_to_kot.maniacmod.voice.dsp;

/*
 * Портовано з sv-voice-changer (https://github.com/sawiq_). Copyright (c) 2026 sawiq_.
 * Ліцензія: CC BY-NC-SA 4.0 — повний текст і умови в voice/dsp/LICENSE-DSP.txt.
 */

/**
 * Split-band de-esser.
 *
 * <p>Shifting a voice downwards moves sibilance down with it, which is why the
 * heavy presets sound muddy without one. The band above {@value #CROSSOVER_HZ} Hz
 * is separated with a one-pole crossover, chosen because low plus high sums
 * back to exactly the input so a bypassed de-esser stays transparent. Its level
 * is compared against the body of the voice and only the excess is attenuated,
 * which keeps ordinary consonants crisp instead of lisping.</p>
 */
public final class DeEsser {
    private static final double CROSSOVER_HZ = 5_200.0D;
    private static final double DETECTOR_SECONDS = 0.004D;
    private static final double RELEASE_SECONDS = 0.060D;
    /** High-band level, relative to the body, that counts as ordinary brightness. */
    private static final double SIBILANCE_ALLOWANCE = 0.55D;

    private final double splitCoefficient;
    private final double detectorCoefficient;
    private final double releaseCoefficient;

    private double lowBand;
    private double sibilantLevel;
    private double bodyLevel;
    private double gain = 1.0D;
    private double amount;

    public DeEsser(double sampleRate) {
        this.splitCoefficient = 1.0D - Math.exp(-2.0D * Math.PI * CROSSOVER_HZ / sampleRate);
        this.detectorCoefficient = 1.0D - Math.exp(-1.0D / (sampleRate * DETECTOR_SECONDS));
        this.releaseCoefficient = 1.0D - Math.exp(-1.0D / (sampleRate * RELEASE_SECONDS));
    }

    /** @param amount 0 bypasses; 1 removes most of the sibilant excess */
    public void setAmount(double amount) {
        this.amount = Math.max(0.0D, Math.min(1.0D, amount));
    }

    public double process(double input) {
        if (this.amount < 0.01D) {
            return input;
        }

        this.lowBand += this.splitCoefficient * (input - this.lowBand);
        double highBand = input - this.lowBand;

        this.sibilantLevel += this.detectorCoefficient * (Math.abs(highBand) - this.sibilantLevel);
        this.bodyLevel += this.detectorCoefficient * (Math.abs(this.lowBand) - this.bodyLevel);

        double allowedHigh = this.bodyLevel * SIBILANCE_ALLOWANCE + 1.0e-5D;
        double excess = this.sibilantLevel / allowedHigh;
        double targetGain = excess > 1.0D
                ? 1.0D / (1.0D + (excess - 1.0D) * this.amount * 3.0D)
                : 1.0D;

        if (targetGain < this.gain) {
            this.gain = targetGain;
        } else {
            this.gain += this.releaseCoefficient * (targetGain - this.gain);
        }

        return this.lowBand + highBand * this.gain;
    }

    public void reset() {
        this.lowBand = 0.0D;
        this.sibilantLevel = 0.0D;
        this.bodyLevel = 0.0D;
        this.gain = 1.0D;
    }
}
