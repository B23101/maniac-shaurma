package com.log_to_kot.maniacmod.voice.dsp;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FFT — фундамент фазового вокодера. Якщо forward/inverse не сходяться
 * з точністю до машинної, то весь pitch/formant-ланцюжок дає сміття, і
 * жоден тест зверху не має сенсу.
 */
class Radix2FftTest {

    @Test
    void forwardThenInverseRoundTrips() {
        int n = 64;
        Radix2Fft fft = new Radix2Fft(n);

        float[] original = new float[2 * n];
        Random random = new Random(42);
        for (int i = 0; i < 2 * n; i++) {
            original[i] = random.nextFloat() * 2f - 1f;
        }

        float[] data = original.clone();
        fft.forward(data);
        fft.inverse(data);

        for (int i = 0; i < 2 * n; i++) {
            assertEquals(original[i], data[i], 1.0e-3f,
                "round trip sample " + i);
        }
    }

    @Test
    void singleBinSinePeaksAtItsBin() {
        int n = 128;
        int targetBin = 9;
        Radix2Fft fft = new Radix2Fft(n);

        float[] data = new float[2 * n];
        for (int i = 0; i < n; i++) {
            data[2 * i] = (float) Math.cos(2.0 * Math.PI * targetBin * i / n);
        }
        fft.forward(data);

        int strongest = 0;
        float strongestMagnitude = -1f;
        for (int bin = 0; bin < n / 2; bin++) {
            float re = data[2 * bin];
            float im = data[2 * bin + 1];
            float magnitude = (float) Math.sqrt(re * re + im * im);
            if (magnitude > strongestMagnitude) {
                strongestMagnitude = magnitude;
                strongest = bin;
            }
        }

        assertTrue(strongest == targetBin,
            "очікували пік у біні " + targetBin + ", отримали " + strongest);
    }

    @Test
    void rejectsNonPowerOfTwo() {
        try {
            new Radix2Fft(100);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("очікували IllegalArgumentException для 100");
    }
}
