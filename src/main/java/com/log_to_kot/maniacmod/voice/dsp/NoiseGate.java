package com.log_to_kot.maniacmod.voice.dsp;

/*
 * Портовано з sv-voice-changer (https://github.com/sawiq_). Copyright (c) 2026 sawiq_.
 * Ліцензія: CC BY-NC-SA 4.0 — повний текст і умови в voice/dsp/LICENSE-DSP.txt.
 */

/**
 * Downward expander used as a speech gate.
 *
 * <p>Runs first in the chain so that room tone, fan noise and keyboard bleed
 * never reach the pitch shifter. That matters more here than in a normal
 * mixer: a phase vocoder turns steady broadband noise into an audible warble,
 * so gating afterwards would be too late.</p>
 *
 * <p>Open and close thresholds differ by a fixed hysteresis, and the gate
 * holds open briefly after the signal drops, so quiet consonants at the end of
 * a word are not chopped off.</p>
 */
public final class NoiseGate {
    private static final double HYSTERESIS_DB = 6.0D;
    private static final double ATTACK_SECONDS = 0.003D;
    private static final double RELEASE_SECONDS = 0.120D;
    private static final double HOLD_SECONDS = 0.150D;
    private static final double DETECTOR_SECONDS = 0.010D;
    /** Floor the gate closes down to. Full silence sounds like a dropped call. */
    private static final double CLOSED_GAIN = 0.02D;

    private final double sampleRate;
    private final double detectorCoefficient;
    private final double attackCoefficient;
    private final double releaseCoefficient;
    private final int holdSamples;

    private double detector;
    private double gain = 1.0D;
    private int holdCounter;
    private double openThreshold;
    private double closeThreshold;
    private boolean bypassed = true;

    public NoiseGate(double sampleRate) {
        this.sampleRate = sampleRate;
        this.detectorCoefficient = coefficientFor(DETECTOR_SECONDS);
        this.attackCoefficient = coefficientFor(ATTACK_SECONDS);
        this.releaseCoefficient = coefficientFor(RELEASE_SECONDS);
        this.holdSamples = (int) Math.round(sampleRate * HOLD_SECONDS);
        setAmount(0.0D);
    }

    /**
     * @param amount 0 disables the gate; 1 is aggressive enough for a noisy
     *               room, at the cost of clipping very quiet speech
     */
    public void setAmount(double amount) {
        double clamped = Math.max(0.0D, Math.min(1.0D, amount));
        this.bypassed = clamped < 0.01D;
        if (this.bypassed) {
            return;
        }

        double thresholdDb = -62.0D + clamped * 30.0D;
        this.openThreshold = Math.pow(10.0D, thresholdDb / 20.0D);
        this.closeThreshold = Math.pow(10.0D, (thresholdDb - HYSTERESIS_DB) / 20.0D);
    }

    public double process(double input) {
        if (this.bypassed) {
            return input;
        }

        double magnitude = Math.abs(input);
        this.detector += this.detectorCoefficient * (magnitude - this.detector);

        boolean shouldOpen = this.gain > 0.5D
                ? this.detector > this.closeThreshold
                : this.detector > this.openThreshold;

        if (shouldOpen) {
            this.holdCounter = this.holdSamples;
        } else if (this.holdCounter > 0) {
            this.holdCounter--;
            shouldOpen = true;
        }

        double targetGain = shouldOpen ? 1.0D : CLOSED_GAIN;
        double coefficient = targetGain > this.gain ? this.attackCoefficient : this.releaseCoefficient;
        this.gain += coefficient * (targetGain - this.gain);

        return input * this.gain;
    }

    /** True while the gate is passing audio. Drives the studio level meter. */
    public boolean isOpen() {
        return this.gain > 0.5D;
    }

    public void reset() {
        this.detector = 0.0D;
        this.gain = 1.0D;
        this.holdCounter = 0;
    }

    private double coefficientFor(double seconds) {
        return 1.0D - Math.exp(-1.0D / (this.sampleRate * seconds));
    }
}
