package com.log_to_kot.maniacmod.voice.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Малі, але самостійні стадії ланцюжка — перевіряються поштучно. */
class UtilityStagesTest {

    private static final double SAMPLE_RATE = 48_000.0D;

    @Test
    void smoothedValueConvergesToTarget() {
        SmoothedValue value = new SmoothedValue(SAMPLE_RATE, 0.01D, 0.0D);
        value.setTarget(1.0D);
        for (int i = 0; i < 48_000; i++) {
            value.next();
        }
        assertEquals(1.0D, value.current(), 1.0e-3D);
    }

    @Test
    void smoothedValueStartsAtInitialValueWithoutRamp() {
        SmoothedValue value = new SmoothedValue(SAMPLE_RATE, 0.01D, 0.5D);
        assertEquals(0.5D, value.next(), 1.0e-9D);
    }

    @Test
    void noiseGateAtZeroAmountIsTransparent() {
        NoiseGate gate = new NoiseGate(SAMPLE_RATE);
        gate.setAmount(0.0D);
        for (int i = 0; i < 1000; i++) {
            double input = Math.sin(i * 0.1D) * 0.3D;
            assertEquals(input, gate.process(input), 1.0e-12D);
        }
    }

    @Test
    void softLimiterBoundsOutputAndNeverExceedsCeiling() {
        SoftLimiter limiter = new SoftLimiter(SAMPLE_RATE);
        for (int i = 0; i < 48_000; i++) {
            double output = limiter.process(3.0D);
            assertTrue(Math.abs(output) <= 1.0D,
                "лімітер випустив |" + output + "| > 1");
        }
    }

    @Test
    void highPassBiquadRemovesDc() {
        Biquad highPass = new Biquad();
        highPass.setHighPass(SAMPLE_RATE, 200.0D, 0.707D);

        double output = 0.0D;
        for (int i = 0; i < 48_000; i++) {
            output = highPass.process(1.0D); // постійний вхід = DC
        }
        assertTrue(Math.abs(output) < 1.0e-3D,
            "DC не придушено: " + output);
    }

    @Test
    void bypassBiquadIsTransparent() {
        Biquad bypass = new Biquad();
        bypass.setBypass();
        double input = 0.42D;
        assertEquals(input, bypass.process(input), 1.0e-12D);
    }

    @Test
    void subOctaveStaysSilentOnSilence() {
        SubOctaveGenerator sub = new SubOctaveGenerator(SAMPLE_RATE);
        for (int i = 0; i < 48_000; i++) {
            assertEquals(0.0D, sub.process(0.0D), 1.0e-6D);
        }
    }

    @Test
    void deEsserAtZeroAmountIsTransparent() {
        DeEsser deEsser = new DeEsser(SAMPLE_RATE);
        deEsser.setAmount(0.0D);
        for (int i = 0; i < 1000; i++) {
            double input = Math.sin(i * 0.2D) * 0.25D;
            assertEquals(input, deEsser.process(input), 1.0e-12D);
        }
    }
}
