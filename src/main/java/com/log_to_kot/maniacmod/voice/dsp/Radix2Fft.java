package com.log_to_kot.maniacmod.voice.dsp;

/*
 * Портовано з sv-voice-changer (https://github.com/sawiq_). Copyright (c) 2026 sawiq_.
 * Ліцензія: CC BY-NC-SA 4.0 — повний текст і умови в voice/dsp/LICENSE-DSP.txt.
 */

/**
 * Minimal in-place radix-2 Cooley-Tukey complex FFT.
 *
 * <p>The algorithm is the classical decimation-in-time variant described in
 * Cooley &amp; Tukey, &quot;An Algorithm for the Machine Calculation of Complex
 * Fourier Series&quot; (1965). The implementation operates on an interleaved
 * real/imaginary <code>float[]</code> of length <code>2 * n</code>, where
 * <code>data[2*k]</code> stores the real part of bin <code>k</code> and
 * <code>data[2*k+1]</code> stores its imaginary part.</p>
 */
public final class Radix2Fft {
    public final int n;
    public final int log2n;
    private final float[] cosTable;
    private final float[] sinTable;
    private final int[] bitReverse;

    public Radix2Fft(int n) {
        if (n <= 0 || Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("FFT size must be a positive power of 2");
        }
        this.n = n;
        this.log2n = Integer.numberOfTrailingZeros(n);
        this.cosTable = new float[n / 2];
        this.sinTable = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double angle = 2.0 * Math.PI * i / n;
            this.cosTable[i] = (float) Math.cos(angle);
            this.sinTable[i] = (float) Math.sin(angle);
        }
        this.bitReverse = new int[n];
        for (int i = 0; i < n; i++) {
            this.bitReverse[i] = Integer.reverse(i) >>> (32 - log2n);
        }
    }

    /**
     * Forward FFT in place. Uses the convention
     * <code>X[k] = sum_n x[n] * exp(-2&pi;i*k*n/N)</code>.
     */
    public void forward(float[] data) {
        // 1. Bit-reversal permutation.
        for (int i = 0; i < n; i++) {
            int j = bitReverse[i];
            if (j > i) {
                int ai = 2 * i;
                int aj = 2 * j;
                float tr = data[ai];
                float ti = data[ai + 1];
                data[ai] = data[aj];
                data[ai + 1] = data[aj + 1];
                data[aj] = tr;
                data[aj + 1] = ti;
            }
        }

        // 2. Butterflies, log2(n) stages.
        for (int size = 2; size <= n; size <<= 1) {
            int half = size >> 1;
            int step = n / size;
            for (int base = 0; base < n; base += size) {
                int twIdx = 0;
                for (int j = 0; j < half; j++) {
                    int a = 2 * (base + j);
                    int b = 2 * (base + j + half);
                    float wr = cosTable[twIdx];
                    float wi = -sinTable[twIdx]; // forward => e^{-i theta}
                    float br = data[b];
                    float bi = data[b + 1];
                    float tr = wr * br - wi * bi;
                    float ti = wr * bi + wi * br;
                    data[b] = data[a] - tr;
                    data[b + 1] = data[a + 1] - ti;
                    data[a] += tr;
                    data[a + 1] += ti;
                    twIdx += step;
                }
            }
        }
    }

    /**
     * Inverse FFT in place, with the standard <code>1/N</code> normalisation.
     * Implemented as <code>conj(FFT(conj(x))) / N</code>.
     */
    public void inverse(float[] data) {
        for (int i = 0; i < n; i++) {
            data[2 * i + 1] = -data[2 * i + 1];
        }
        forward(data);
        float scale = 1f / n;
        for (int i = 0; i < n; i++) {
            data[2 * i] *= scale;
            data[2 * i + 1] = -data[2 * i + 1] * scale;
        }
    }
}
