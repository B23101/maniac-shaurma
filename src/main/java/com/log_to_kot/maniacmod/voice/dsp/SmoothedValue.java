package com.log_to_kot.maniacmod.voice.dsp;

/*
 * Портовано з sv-voice-changer (https://github.com/sawiq_). Copyright (c) 2026 sawiq_.
 * Ліцензія: CC BY-NC-SA 4.0 — повний текст і умови в voice/dsp/LICENSE-DSP.txt.
 */

/**
 * One-pole smoother for a control value that the UI can change at any moment.
 *
 * <p>Every knob that scales the audio directly is routed through one of these.
 * Jumping a gain, a mix or a delay time straight to its new value produces a
 * step in the waveform, which is exactly the click users hear when switching
 * presets mid-sentence.</p>
 */
public final class SmoothedValue {
    private final double coefficient;
    private double current;
    private double target;

    /**
     * @param sampleRate   stream sample rate
     * @param timeConstant seconds to cover most of the distance to a new target
     * @param initialValue starting value; no ramp is applied to it
     */
    public SmoothedValue(double sampleRate, double timeConstant, double initialValue) {
        this.coefficient = 1.0D - Math.exp(-1.0D / (sampleRate * Math.max(1.0e-6D, timeConstant)));
        this.current = initialValue;
        this.target = initialValue;
    }

    public void setTarget(double value) {
        this.target = value;
    }

    /** Jumps straight to a value without ramping. Use only when (re)starting a stream. */
    public void reset(double value) {
        this.current = value;
        this.target = value;
    }

    public double next() {
        this.current += this.coefficient * (this.target - this.current);
        return this.current;
    }

    public double current() {
        return this.current;
    }
}
