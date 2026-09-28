package com.log_to_kot.maniacmod.voice.dsp;

/*
 * Портовано з sv-voice-changer (https://github.com/sawiq_). Copyright (c) 2026 sawiq_.
 * Ліцензія: CC BY-NC-SA 4.0 — повний текст і умови в voice/dsp/LICENSE-DSP.txt.
 */

import java.util.Arrays;

/**
 * STFT phase vocoder that shifts pitch and vocal-tract length independently.
 *
 * <p>The base technique is Bernsee's smbPitchShift (published on
 * dspdimension.com under the Wide Open License and re-implemented here from
 * the algorithm description), extended in three ways that matter for
 * speech:</p>
 *
 * <ol>
 *   <li><b>Separate formant control.</b> Each frame is split into a spectral
 *       envelope, obtained by cepstral liftering, and the excitation left over
 *       once the envelope is divided out. Only the excitation is moved by the
 *       pitch ratio; the envelope is resampled by its own ratio. Shifting both
 *       together is what makes a naive pitch shifter sound like a chipmunk,
 *       and keeping them apart is what lets a voice be raised a musical fourth
 *       and still sound like a person.</li>
 *   <li><b>Dominant-partial bin mapping.</b> Shifting down maps several source
 *       bins onto one target bin. Magnitudes add, but a bin can carry only one
 *       frequency, and it has to be the one of the partial that actually
 *       dominates it rather than whichever source bin was visited last.</li>
 *   <li><b>Energy matching.</b> Bin redistribution neither preserves nor
 *       bounds energy, so the output level is corrected per frame and
 *       smoothed across frames.</li>
 * </ol>
 *
 * <p>Streaming convention: the caller passes a sliding window of
 * {@link #fftSize} samples in which the newest {@link #stepSize()} samples are
 * new, and receives one {@code stepSize} block of output per call. Output lags
 * input by {@link #latencySamples()}.</p>
 */
public final class VocalPitchShifter {
    /**
     * Narrowest spectral feature the envelope is allowed to keep, in Hz.
     *
     * <p>A cepstral coefficient at quefrency {@code q} describes a ripple in
     * the log spectrum whose period is {@code sampleRate / q} Hz, so the lifter
     * cut-off has to be derived from the sample rate rather than fixed. Adult
     * speech puts the first two formants roughly 500 Hz apart, so the envelope
     * has to resolve at least that much or it merges them into one bump and
     * stops describing a vocal tract at all.</p>
     */
    private static final double ENVELOPE_RESOLUTION_HZ = 400.0;
    private static final float MIN_ENVELOPE = 1.0e-6f;
    private static final float MAX_GAIN_CORRECTION = 4f;
    private static final float MIN_GAIN_CORRECTION = 0.25f;
    private static final float GAIN_CORRECTION_SMOOTHING = 0.30f;
    /**
     * Hops between spectral envelope estimates. The envelope tracks the shape
     * of the vocal tract, which moves at syllable rates; re-deriving it on
     * every hop spends two thirds of the transform budget re-computing an
     * almost identical curve.
     */
    private static final int ENVELOPE_REFRESH_HOPS = 2;

    private final int fftSize;
    private final int stepSize;
    private final int half;
    private final int osamp;
    private final double expectedPhaseIncrement;
    private final float freqPerBin;
    private final float overlapAddScale;
    private final int lifterCutoff;

    private final Radix2Fft fft;
    private final float[] analysisWindow;

    private final float[] spectrum;
    private final float[] analysisMagnitude;
    private final float[] analysisFrequency;
    private final float[] excitation;
    private final float[] envelope;
    private final float[] shiftedMagnitude;
    private final float[] shiftedFrequency;
    private final float[] strongestContribution;
    private final float[] previousPhase;
    private final double[] summedPhase;
    private final float[] outputAccumulator;
    private final float[] cepstrum;

    private float pitchRatio = 1f;
    private float formantRatio = 1f;
    private float smoothedGainCorrection = 1f;
    private int hopsSinceEnvelope = Integer.MAX_VALUE;

    public VocalPitchShifter(float sampleRate, int fftSize, int stepSize) {
        if (Integer.bitCount(fftSize) != 1) {
            throw new IllegalArgumentException("fftSize must be a power of two");
        }
        if (stepSize <= 0 || fftSize % stepSize != 0) {
            throw new IllegalArgumentException("fftSize must be an integer multiple of stepSize");
        }

        this.fftSize = fftSize;
        this.stepSize = stepSize;
        this.half = fftSize / 2;
        this.osamp = fftSize / stepSize;
        this.expectedPhaseIncrement = 2.0 * Math.PI * stepSize / fftSize;
        this.freqPerBin = sampleRate / fftSize;
        // The Hann window is applied on analysis and again on synthesis, so
        // each output sample sums w[n]^2 over osamp overlapping frames. For a
        // Hann window that sum is the constant 3*osamp/8, so dividing by it
        // gives unity gain at ratio 1. Bernsee's C source folds a different
        // constant in because his inverse FFT is un-normalised; ours is.
        this.overlapAddScale = 8f / (3f * this.osamp);

        // Also kept well below the quefrency of the highest voice we expect to
        // handle, so the lifter never mistakes a child's pitch for a formant.
        this.lifterCutoff = Math.max(4, Math.min(fftSize / 4,
                (int) Math.round(sampleRate / ENVELOPE_RESOLUTION_HZ)));

        this.fft = new Radix2Fft(fftSize);
        this.analysisWindow = new float[fftSize];
        for (int i = 0; i < fftSize; i++) {
            this.analysisWindow[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / fftSize));
        }

        this.spectrum = new float[2 * fftSize];
        this.analysisMagnitude = new float[this.half];
        this.analysisFrequency = new float[this.half];
        this.excitation = new float[this.half];
        this.envelope = new float[this.half];
        this.shiftedMagnitude = new float[this.half];
        this.shiftedFrequency = new float[this.half];
        this.strongestContribution = new float[this.half];
        this.previousPhase = new float[this.half];
        this.summedPhase = new double[this.half];
        this.outputAccumulator = new float[2 * fftSize];
        this.cepstrum = new float[2 * fftSize];
    }

    public int stepSize() {
        return this.stepSize;
    }

    /** Quefrency bins kept when estimating the spectral envelope. */
    public int lifterCutoff() {
        return this.lifterCutoff;
    }

    /** Samples of delay this stage introduces between input and output. */
    public int latencySamples() {
        return this.fftSize - this.stepSize;
    }

    /** Multiplier applied to the fundamental and its harmonics. */
    public void setPitchRatio(float ratio) {
        this.pitchRatio = ratio;
    }

    /**
     * Multiplier applied to the spectral envelope. Below 1 the speaker sounds
     * physically larger, above 1 smaller. Independent of the pitch ratio.
     */
    public void setFormantRatio(float ratio) {
        this.formantRatio = ratio;
    }

    /** True when both ratios are close enough to 1 that the transform is the identity. */
    public boolean isTransparent() {
        return Math.abs(this.pitchRatio - 1f) < 0.002f && Math.abs(this.formantRatio - 1f) < 0.002f;
    }

    public void reset() {
        Arrays.fill(this.previousPhase, 0f);
        Arrays.fill(this.summedPhase, 0.0);
        Arrays.fill(this.outputAccumulator, 0f);
        this.smoothedGainCorrection = 1f;
        this.hopsSinceEnvelope = Integer.MAX_VALUE;
    }

    /**
     * Processes one STFT hop.
     *
     * @param window   at least {@link #fftSize} samples, newest last
     * @param outBlock receives {@link #stepSize()} output samples
     */
    public void process(float[] window, float[] outBlock) {
        if (window.length < this.fftSize) {
            throw new IllegalArgumentException("window must hold at least fftSize samples");
        }
        if (outBlock.length < this.stepSize) {
            throw new IllegalArgumentException("outBlock must hold at least stepSize samples");
        }

        analyse(window);

        // At neutral ratios the transform is the identity, so the envelope
        // estimation and bin redistribution are skipped. The phase machinery
        // still runs, which keeps latency and output continuity identical
        // whether or not the user is currently shifting anything: crossing
        // pitch 1.0 with a slider must not click.
        if (isTransparent()) {
            System.arraycopy(this.analysisMagnitude, 0, this.shiftedMagnitude, 0, this.half);
            System.arraycopy(this.analysisFrequency, 0, this.shiftedFrequency, 0, this.half);
        } else {
            double inputEnergy = splitEnvelopeFromExcitation();
            shiftSpectrum();
            applyShiftedEnvelope();
            matchEnergy(inputEnergy);
        }

        synthesise();
        overlapAdd(outBlock);
    }

    /** Windows the input, transforms it, and derives magnitude and true frequency per bin. */
    private void analyse(float[] window) {
        for (int i = 0; i < this.fftSize; i++) {
            this.spectrum[2 * i] = window[i] * this.analysisWindow[i];
            this.spectrum[2 * i + 1] = 0f;
        }
        this.fft.forward(this.spectrum);

        for (int i = 0; i < this.half; i++) {
            float real = this.spectrum[2 * i];
            float imaginary = this.spectrum[2 * i + 1];
            float magnitude = (float) (2.0 * Math.sqrt(real * real + imaginary * imaginary));
            float phase = (float) Math.atan2(imaginary, real);

            double phaseDelta = phase - this.previousPhase[i];
            this.previousPhase[i] = phase;

            // Remove the phase advance a bin at its own centre frequency would
            // have accumulated over one hop; what is left is the deviation
            // that reveals the partial's true frequency.
            phaseDelta -= i * this.expectedPhaseIncrement;
            phaseDelta = wrapToPi(phaseDelta);

            double deviationInBins = this.osamp * phaseDelta / (2.0 * Math.PI);

            this.analysisMagnitude[i] = magnitude;
            this.analysisFrequency[i] = (float) ((i + deviationInBins) * this.freqPerBin);
        }
    }

    /**
     * Estimates the spectral envelope and divides it out, leaving the
     * excitation. Returns the frame's input energy for later gain matching.
     */
    private double splitEnvelopeFromExcitation() {
        double inputEnergy = 0.0;
        for (int i = 0; i < this.half; i++) {
            inputEnergy += this.analysisMagnitude[i] * this.analysisMagnitude[i];
        }

        if (this.hopsSinceEnvelope >= ENVELOPE_REFRESH_HOPS) {
            estimateEnvelope();
            this.hopsSinceEnvelope = 0;
        } else {
            this.hopsSinceEnvelope++;
        }

        for (int i = 0; i < this.half; i++) {
            float envelopeValue = this.envelope[i];
            this.excitation[i] = envelopeValue > MIN_ENVELOPE
                    ? this.analysisMagnitude[i] / envelopeValue
                    : this.analysisMagnitude[i];
        }
        return inputEnergy;
    }

    /**
     * Smoothed log-magnitude spectrum via cepstral liftering: log magnitude,
     * inverse transform to the cepstrum, discard the high-quefrency
     * coefficients that carry the harmonic comb, transform back, exponentiate.
     */
    private void estimateEnvelope() {
        for (int i = 0; i < this.half; i++) {
            float logMagnitude = (float) Math.log(Math.max(MIN_ENVELOPE, this.analysisMagnitude[i]));
            this.cepstrum[2 * i] = logMagnitude;
            this.cepstrum[2 * i + 1] = 0f;
        }
        // Mirror into the upper half so the cepstrum comes out real.
        for (int i = this.half; i < this.fftSize; i++) {
            int mirrored = this.fftSize - i;
            this.cepstrum[2 * i] = this.cepstrum[2 * Math.min(mirrored, this.half - 1)];
            this.cepstrum[2 * i + 1] = 0f;
        }

        this.fft.inverse(this.cepstrum);

        for (int i = this.lifterCutoff; i < this.fftSize - this.lifterCutoff; i++) {
            this.cepstrum[2 * i] = 0f;
            this.cepstrum[2 * i + 1] = 0f;
        }

        this.fft.forward(this.cepstrum);

        for (int i = 0; i < this.half; i++) {
            this.envelope[i] = (float) Math.exp(this.cepstrum[2 * i]);
        }
    }

    /**
     * Moves the excitation, and only the excitation, by the pitch ratio.
     *
     * <p>Shifting down maps several source bins onto one target bin.
     * Magnitudes add up, but a bin can carry only one frequency, so it is
     * taken from the partial that dominates the bin. Keeping whichever source
     * bin happened to be written last would instead take it from a bin up to
     * {@code 1 / ratio} positions away from the dominant one, biased
     * consistently upwards.</p>
     */
    private void shiftSpectrum() {
        Arrays.fill(this.shiftedMagnitude, 0f);
        Arrays.fill(this.shiftedFrequency, 0f);
        Arrays.fill(this.strongestContribution, 0f);

        for (int i = 0; i < this.half; i++) {
            int target = Math.round(i * this.pitchRatio);
            if (target < 0 || target >= this.half) {
                continue;
            }

            this.shiftedMagnitude[target] += this.excitation[i];

            float contribution = this.analysisMagnitude[i];
            if (contribution >= this.strongestContribution[target]) {
                this.strongestContribution[target] = contribution;
                this.shiftedFrequency[target] = this.analysisFrequency[i] * this.pitchRatio;
            }
        }
    }

    /** Re-applies the envelope, resampled by the formant ratio. */
    private void applyShiftedEnvelope() {
        for (int i = 0; i < this.half; i++) {
            this.shiftedMagnitude[i] *= sampleEnvelope(i / this.formantRatio);
        }
    }

    /** Linear interpolation into the envelope, clamped at both ends. */
    private float sampleEnvelope(float position) {
        if (position <= 0f) {
            return this.envelope[0];
        }
        if (position >= this.half - 1) {
            return this.envelope[this.half - 1];
        }

        int lower = (int) position;
        float fraction = position - lower;
        return this.envelope[lower] + (this.envelope[lower + 1] - this.envelope[lower]) * fraction;
    }

    private void matchEnergy(double inputEnergy) {
        double outputEnergy = 0.0;
        for (int i = 0; i < this.half; i++) {
            outputEnergy += this.shiftedMagnitude[i] * this.shiftedMagnitude[i];
        }

        if (outputEnergy > 1.0e-12 && inputEnergy > 1.0e-12) {
            float instantGain = (float) Math.sqrt(inputEnergy / outputEnergy);
            instantGain = Math.max(MIN_GAIN_CORRECTION, Math.min(MAX_GAIN_CORRECTION, instantGain));
            this.smoothedGainCorrection += GAIN_CORRECTION_SMOOTHING * (instantGain - this.smoothedGainCorrection);
        }

        for (int i = 0; i < this.half; i++) {
            this.shiftedMagnitude[i] *= this.smoothedGainCorrection;
        }
    }

    /**
     * Rebuilds the complex spectrum, accumulating each bin's phase from its
     * measured frequency.
     *
     * <p>Straight accumulation is used deliberately, rather than Laroche and
     * Dolson's identity phase locking: on this shifter the latter measurably
     * corrupted the harmonic series once the spectrum had been redistributed
     * (a subharmonic below the fundamental at 0.72, a dropped third harmonic
     * at 1.45). Straight accumulation measures clean at every ratio tested.</p>
     */
    private void synthesise() {
        for (int i = 0; i < this.half; i++) {
            this.summedPhase[i] += phaseAdvanceFor(i);

            double magnitude = this.shiftedMagnitude[i];
            double phase = this.summedPhase[i];
            this.spectrum[2 * i] = (float) (magnitude * Math.cos(phase));
            this.spectrum[2 * i + 1] = (float) (magnitude * Math.sin(phase));
        }

        for (int i = this.half; i < this.fftSize; i++) {
            this.spectrum[2 * i] = 0f;
            this.spectrum[2 * i + 1] = 0f;
        }
    }

    private double phaseAdvanceFor(int bin) {
        double deviationInBins = this.shiftedFrequency[bin] / this.freqPerBin - bin;
        return 2.0 * Math.PI * deviationInBins / this.osamp + bin * this.expectedPhaseIncrement;
    }

    private void overlapAdd(float[] outBlock) {
        this.fft.inverse(this.spectrum);

        for (int i = 0; i < this.fftSize; i++) {
            this.outputAccumulator[i] += this.analysisWindow[i] * this.spectrum[2 * i] * this.overlapAddScale;
        }

        System.arraycopy(this.outputAccumulator, 0, outBlock, 0, this.stepSize);
        System.arraycopy(this.outputAccumulator, this.stepSize, this.outputAccumulator, 0, this.fftSize);
        Arrays.fill(this.outputAccumulator, this.fftSize, this.fftSize + this.stepSize, 0f);
    }

    private static double wrapToPi(double phase) {
        long quadrants = (long) (phase / Math.PI);
        quadrants += quadrants >= 0 ? (quadrants & 1L) : -(quadrants & 1L);
        return phase - Math.PI * quadrants;
    }
}
