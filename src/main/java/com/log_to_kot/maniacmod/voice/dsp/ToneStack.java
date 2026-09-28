package com.log_to_kot.maniacmod.voice.dsp;

/*
 * Портовано з sv-voice-changer (https://github.com/sawiq_). Copyright (c) 2026 sawiq_.
 * Ліцензія: CC BY-NC-SA 4.0 — повний текст і умови в voice/dsp/LICENSE-DSP.txt.
 */

/**
 * The tone section: a three-band EQ and a switchable telephone band.
 *
 * <p>The bands are real biquad shelving and peaking filters at fixed corner
 * frequencies rather than overlapping one-pole splits, so a boost does what
 * its label says and the three controls stay independent of one another.</p>
 *
 * <p>The radio character is an explicit control here. Deriving it from the EQ
 * settings, as an earlier version did, meant that anyone cutting bass and
 * treble for an unrelated reason silently got a walkie-talkie.</p>
 *
 * <p>У maniacmod радіо-режим не використовується (голос маньяка його не
 * вмикає), але клас лишається повним портом — прибирати гілки означало б
 * редагувати перевірений алгоритм без потреби.</p>
 */
public final class ToneStack {
    private static final double LOW_SHELF_HZ = 220.0D;
    private static final double MID_PEAK_HZ = 1_100.0D;
    private static final double MID_Q = 0.9D;
    private static final double HIGH_SHELF_HZ = 4_200.0D;

    private static final double RADIO_LOW_CUT_HZ = 380.0D;
    private static final double RADIO_HIGH_CUT_HZ = 2_900.0D;
    private static final double RADIO_PRESENCE_HZ = 1_800.0D;
    private static final double RADIO_PRESENCE_Q = 1.4D;
    private static final double RADIO_PRESENCE_DB = 4.0D;

    private final double sampleRate;

    private final Biquad lowShelf = new Biquad();
    private final Biquad midPeak = new Biquad();
    private final Biquad highShelf = new Biquad();
    private final Biquad radioLowCut = new Biquad();
    private final Biquad radioHighCut = new Biquad();
    private final Biquad radioPresence = new Biquad();

    private final SmoothedValue radioAmount;

    public ToneStack(double sampleRate) {
        this.sampleRate = sampleRate;
        this.radioAmount = new SmoothedValue(sampleRate, 0.05D, 0.0D);

        this.radioLowCut.setHighPass(sampleRate, RADIO_LOW_CUT_HZ, 0.707D);
        this.radioHighCut.setLowPass(sampleRate, RADIO_HIGH_CUT_HZ, 0.707D);
        this.radioPresence.setPeaking(sampleRate, RADIO_PRESENCE_HZ, RADIO_PRESENCE_Q, RADIO_PRESENCE_DB);

        setEqualiser(0.0D, 0.0D, 0.0D);
    }

    public void setEqualiser(double lowDb, double midDb, double highDb) {
        this.lowShelf.setLowShelf(this.sampleRate, LOW_SHELF_HZ, lowDb);
        this.midPeak.setPeaking(this.sampleRate, MID_PEAK_HZ, MID_Q, midDb);
        this.highShelf.setHighShelf(this.sampleRate, HIGH_SHELF_HZ, highDb);
    }

    /** @param amount 0 leaves the full bandwidth, 1 is a handheld radio */
    public void setRadioAmount(double amount) {
        this.radioAmount.setTarget(Math.max(0.0D, Math.min(1.0D, amount)));
    }

    public double process(double input) {
        double filtered = this.lowShelf.process(input);
        filtered = this.midPeak.process(filtered);
        filtered = this.highShelf.process(filtered);

        double radioBlend = this.radioAmount.next();
        if (radioBlend < 0.001D) {
            return filtered;
        }

        double banded = this.radioPresence.process(this.radioHighCut.process(this.radioLowCut.process(filtered)));
        return filtered + (banded - filtered) * radioBlend;
    }

    public void reset() {
        this.lowShelf.reset();
        this.midPeak.reset();
        this.highShelf.reset();
        this.radioLowCut.reset();
        this.radioHighCut.reset();
        this.radioPresence.reset();
        this.radioAmount.reset(this.radioAmount.current());
    }
}
