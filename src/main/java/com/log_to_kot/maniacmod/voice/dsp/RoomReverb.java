package com.log_to_kot.maniacmod.voice.dsp;

/*
 * Портовано з sv-voice-changer (https://github.com/sawiq_). Copyright (c) 2026 sawiq_.
 * Ліцензія: CC BY-NC-SA 4.0 — повний текст і умови в voice/dsp/LICENSE-DSP.txt.
 */

/**
 * Schroeder-Moorer reverberator: parallel damped comb filters feeding a chain
 * of allpass diffusers.
 *
 * <p>This replaces a single feedback delay tap, which produces discrete
 * repeats rather than a tail. On a voice the difference is the whole point of
 * the Titan and Demon presets: eight combs at mutually coprime delays fill in
 * the gaps between echoes densely enough to read as a room, and the allpass
 * stages smear the remaining flutter.</p>
 *
 * <p>Comb delays are the classic Freeverb set, rescaled from 44.1 kHz to this
 * chain's 48 kHz, staying coprime afterwards. That is what keeps their repeats
 * from lining up into an audible ringing pitch.</p>
 */
public final class RoomReverb {
    private static final int[] COMB_DELAYS_48K = {1_699, 1_759, 1_621, 1_549, 1_391, 1_477, 1_291, 1_213};
    private static final int[] ALLPASS_DELAYS_48K = {607, 479, 373, 251};
    private static final double ALLPASS_FEEDBACK = 0.5D;
    private static final double DAMPING = 0.32D;
    /** Shortest room the size control can reach, as a fraction of the full delay. */
    private static final double MIN_SIZE_SCALE = 0.30D;
    private static final double MIN_FEEDBACK = 0.62D;
    private static final double MAX_FEEDBACK = 0.90D;

    private final float[][] combBuffers;
    private final int[] combWriteIndex;
    private final double[] combDamperState;
    private final SmoothedValue[] combDelay;

    private final float[][] allpassBuffers;
    private final int[] allpassWriteIndex;

    private double feedback = MIN_FEEDBACK;

    public RoomReverb(double sampleRate) {
        double rateScale = sampleRate / 48_000.0D;

        this.combBuffers = new float[COMB_DELAYS_48K.length][];
        this.combWriteIndex = new int[COMB_DELAYS_48K.length];
        this.combDamperState = new double[COMB_DELAYS_48K.length];
        this.combDelay = new SmoothedValue[COMB_DELAYS_48K.length];
        for (int i = 0; i < COMB_DELAYS_48K.length; i++) {
            int length = Math.max(4, (int) Math.round(COMB_DELAYS_48K[i] * rateScale));
            this.combBuffers[i] = new float[length];
            // Half a second to travel the full size range: slow enough that
            // dragging the size slider glides instead of stepping.
            this.combDelay[i] = new SmoothedValue(sampleRate, 0.5D, length);
        }

        this.allpassBuffers = new float[ALLPASS_DELAYS_48K.length][];
        this.allpassWriteIndex = new int[ALLPASS_DELAYS_48K.length];
        for (int i = 0; i < ALLPASS_DELAYS_48K.length; i++) {
            this.allpassBuffers[i] = new float[Math.max(4, (int) Math.round(ALLPASS_DELAYS_48K[i] * rateScale))];
        }

        setRoom(0.4D, 0.5D);
    }

    /**
     * @param size  0 is a booth, 1 is a hall
     * @param decay 0 is a short slap, 1 is a long tail
     */
    public void setRoom(double size, double decay) {
        double clampedSize = Math.max(0.0D, Math.min(1.0D, size));
        double clampedDecay = Math.max(0.0D, Math.min(1.0D, decay));

        double sizeScale = MIN_SIZE_SCALE + (1.0D - MIN_SIZE_SCALE) * clampedSize;
        for (int i = 0; i < this.combBuffers.length; i++) {
            double target = Math.max(4.0D, this.combBuffers[i].length * sizeScale);
            this.combDelay[i].setTarget(target);
        }

        this.feedback = MIN_FEEDBACK + (MAX_FEEDBACK - MIN_FEEDBACK) * clampedDecay;
    }

    /** @return the reverberated signal only; the caller mixes it with the dry path */
    public double process(double input) {
        double combSum = 0.0D;
        for (int i = 0; i < this.combBuffers.length; i++) {
            combSum += processComb(i, input);
        }
        combSum /= this.combBuffers.length;

        double diffused = combSum;
        for (int i = 0; i < this.allpassBuffers.length; i++) {
            diffused = processAllpass(i, diffused);
        }
        return diffused;
    }

    public void reset() {
        for (int i = 0; i < this.combBuffers.length; i++) {
            java.util.Arrays.fill(this.combBuffers[i], 0f);
            this.combWriteIndex[i] = 0;
            this.combDamperState[i] = 0.0D;
            this.combDelay[i].reset(this.combDelay[i].current());
        }
        for (int i = 0; i < this.allpassBuffers.length; i++) {
            java.util.Arrays.fill(this.allpassBuffers[i], 0f);
            this.allpassWriteIndex[i] = 0;
        }
    }

    private double processComb(int index, double input) {
        float[] buffer = this.combBuffers[index];
        int write = this.combWriteIndex[index];
        int delay = Math.max(1, Math.min(buffer.length, (int) Math.round(this.combDelay[index].next())));

        int read = write - delay;
        if (read < 0) {
            read += buffer.length;
        }
        double delayed = buffer[read];

        // One-pole lowpass inside the feedback loop: successive repeats lose
        // their top end, the way a real room absorbs high frequencies.
        this.combDamperState[index] = delayed * (1.0D - DAMPING) + this.combDamperState[index] * DAMPING;

        double written = input + this.combDamperState[index] * this.feedback;
        buffer[write] = (float) (Double.isFinite(written) ? written : 0.0D);
        this.combWriteIndex[index] = write + 1 >= buffer.length ? 0 : write + 1;

        return delayed;
    }

    private double processAllpass(int index, double input) {
        float[] buffer = this.allpassBuffers[index];
        int write = this.allpassWriteIndex[index];

        double delayed = buffer[write];
        double output = delayed - input;
        buffer[write] = (float) (input + delayed * ALLPASS_FEEDBACK);
        this.allpassWriteIndex[index] = write + 1 >= buffer.length ? 0 : write + 1;

        return output;
    }
}
