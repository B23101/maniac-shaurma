package com.log_to_kot.maniacmod.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Масштабування профілю силою 0..1 — межа між «вимкнено» і «повний пресет». */
class ManiacVoiceProfileTest {

    @Test
    void zeroStrengthIsExactlyNeutral() {
        assertEquals(ManiacVoiceProfile.NEUTRAL, ManiacVoiceProfile.DEMON.scaled(0.0D));
    }

    @Test
    void fullStrengthReturnsSameProfile() {
        assertSame(ManiacVoiceProfile.DEMON, ManiacVoiceProfile.DEMON.scaled(1.0D));
    }

    @Test
    void strengthOutsideRangeIsClamped() {
        assertEquals(ManiacVoiceProfile.NEUTRAL, ManiacVoiceProfile.DEMON.scaled(-5.0D));
        assertSame(ManiacVoiceProfile.DEMON, ManiacVoiceProfile.DEMON.scaled(2.0D));
    }

    @Test
    void halfStrengthLerpsPitchTowardsNeutral() {
        ManiacVoiceProfile half = ManiacVoiceProfile.DEMON.scaled(0.5D);
        double expected = (ManiacVoiceProfile.NEUTRAL.pitch() + ManiacVoiceProfile.DEMON.pitch()) / 2.0D;
        assertEquals(expected, half.pitch(), 1.0e-9D);
    }

    @Test
    void demonValuesMatchTheDocumentedPreset() {
        assertEquals(0.68D, ManiacVoiceProfile.DEMON.pitch(), 1.0e-9D);
        assertEquals(0.76D, ManiacVoiceProfile.DEMON.formant(), 1.0e-9D);
        assertEquals(0.50D, ManiacVoiceProfile.DEMON.growl(), 1.0e-9D);
        assertEquals(0.26D, ManiacVoiceProfile.DEMON.distortion(), 1.0e-9D);
    }
}
