package com.log_to_kot.maniacmod.voice.dsp;

/*
 * Портовано з sv-voice-changer (https://github.com/sawiq_). Copyright (c) 2026 sawiq_.
 * Ліцензія: CC BY-NC-SA 4.0 — повний текст і умови в voice/dsp/LICENSE-DSP.txt.
 */

/**
 * Analogue-style octave divider: the chest layer behind the Batman, Titan and
 * Demon voices.
 *
 * <p>The input is band-limited to the fundamental region, a flip-flop toggles
 * on every upward zero crossing to produce a square at half the input
 * frequency, and that square is multiplied back into the filtered signal. The
 * product carries a strong component one octave below the voice, which is then
 * smoothed and level-matched to the input envelope.</p>
 *
 * <p>The predecessor advanced a sine phase by half a period on every sign
 * change of the full-band signal. On real speech the sign changes constantly
 * on noise, so the "sub" tracked nothing and mostly added grit. Deriving the
 * divider from a low-passed copy is what makes it track the actual voice.</p>
 */
public final class SubOctaveGenerator {
    private static final double TRACKING_LOW_PASS_HZ = 420.0D;
    private static final double OUTPUT_LOW_PASS_HZ = 900.0D;
    private static final double ENVELOPE_SECONDS = 0.020D;
    /** Level below which the divider is muted, so silence stays silent. */
    private static final double GATE_LEVEL = 0.004D;

    private final double trackingCoefficient;
    private final double outputCoefficient;
    private final double envelopeCoefficient;

    private double trackingState;
    private double outputState;
    private double envelope;
    private double previousTracking;
    private double flipFlop = 1.0D;

    public SubOctaveGenerator(double sampleRate) {
        this.trackingCoefficient = 1.0D - Math.exp(-2.0D * Math.PI * TRACKING_LOW_PASS_HZ / sampleRate);
        this.outputCoefficient = 1.0D - Math.exp(-2.0D * Math.PI * OUTPUT_LOW_PASS_HZ / sampleRate);
        this.envelopeCoefficient = 1.0D - Math.exp(-1.0D / (sampleRate * ENVELOPE_SECONDS));
    }

    /** @return the sub-octave layer alone; the caller decides how much to add */
    public double process(double input) {
        this.trackingState += this.trackingCoefficient * (input - this.trackingState);
        this.envelope += this.envelopeCoefficient * (Math.abs(input) - this.envelope);

        if (this.previousTracking <= 0.0D && this.trackingState > 0.0D) {
            this.flipFlop = -this.flipFlop;
        }
        this.previousTracking = this.trackingState;

        if (this.envelope < GATE_LEVEL) {
            this.outputState += this.outputCoefficient * (0.0D - this.outputState);
            return this.outputState;
        }

        double divided = this.trackingState * this.flipFlop;
        this.outputState += this.outputCoefficient * (divided - this.outputState);
        return this.outputState;
    }

    public void reset() {
        this.trackingState = 0.0D;
        this.outputState = 0.0D;
        this.envelope = 0.0D;
        this.previousTracking = 0.0D;
        this.flipFlop = 1.0D;
    }
}
