package com.log_to_kot.maniacmod.voice.dsp;

import com.log_to_kot.maniacmod.voice.ManiacVoiceProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ланцюжок голосу маньяка як ціле — без Minecraft.
 *
 * Тести не порівнюють звук із «еталоном» (його немає й не може бути без
 * вуха), а перевіряють ті властивості, які МОЖНА перевірити чисельно:
 * зміну висоти тону, детермінованість, скінченність і поведінку на тиші.
 */
class ManiacVoiceChainTest {

    private static final int SAMPLE_RATE = 48_000;

    @Test
    void outputLengthIsUnchanged() {
        short[] input = tone(440.0D, 0.3D, SAMPLE_RATE);
        short[] output = render(ManiacVoiceProfile.DEMON, input);
        assertEquals(input.length, output.length);
    }

    @Test
    void processIsDeterministic() {
        short[] input = tone(200.0D, 0.4D, SAMPLE_RATE);
        short[] first = render(ManiacVoiceProfile.DEMON, input);
        short[] second = render(ManiacVoiceProfile.DEMON, input);
        assertArrayEquals(first, second, "той самий вхід має давати той самий вихід");
    }

    @Test
    void demonLowersDominantFrequency() {
        double inputHz = 200.0D;
        short[] input = tone(inputHz, 0.5D, SAMPLE_RATE);
        short[] output = render(ManiacVoiceProfile.DEMON, input);

        double outputHz = dominantFrequency(output, SAMPLE_RATE, 40.0D, 260.0D);
        assertTrue(outputHz > 0.0D, "не вдалося визначити частоту виходу");
        assertTrue(outputHz < inputHz * 0.85D,
            "очікували зсув тону вниз: вхід " + inputHz + " Гц, вихід " + outputHz + " Гц");
    }

    @Test
    void loudSignalStaysFiniteAndNonSilent() {
        short[] input = tone(160.0D, 0.9D, SAMPLE_RATE);
        short[] output = render(ManiacVoiceProfile.DEMON, input);

        boolean anyNonZero = false;
        for (short sample : output) {
            if (sample != 0) {
                anyNonZero = true;
                break;
            }
        }
        assertTrue(anyNonZero, "ланцюжок заглушив сигнал повністю");
    }

    @Test
    void silenceStaysSilentAfterReset() {
        ManiacVoiceChain chain = new ManiacVoiceChain(SAMPLE_RATE);
        chain.reset();
        chain.configure(ManiacVoiceProfile.DEMON);

        short[] silence = new short[SAMPLE_RATE / 2];
        chain.process(silence);

        for (short sample : silence) {
            assertEquals(0, sample, "на тиші ланцюжок не повинен генерувати сигнал");
        }
    }

    @Test
    void neutralProfilePreservesEnergyWithinAnOrderOfMagnitude() {
        short[] input = tone(300.0D, 0.3D, SAMPLE_RATE);
        short[] output = render(ManiacVoiceProfile.NEUTRAL, input);

        double inputEnergy = energy(input);
        double outputEnergy = energy(output);
        assertTrue(outputEnergy > 0.0D, "вихід порожній");

        double ratio = outputEnergy / inputEnergy;
        assertTrue(ratio > 0.1D && ratio < 10.0D,
            "нейтральний профіль змінив енергію за межі порядку: " + ratio);
    }

    // ── Допоміжне ────────────────────────────────────────────────────────

    private static short[] render(ManiacVoiceProfile profile, short[] input) {
        ManiacVoiceChain chain = new ManiacVoiceChain(SAMPLE_RATE);
        chain.reset();
        chain.configure(profile);
        short[] output = input.clone();
        chain.process(output);
        return output;
    }

    private static short[] tone(double frequency, double amplitude, int sampleRate) {
        int length = sampleRate * 2;
        short[] out = new short[length];
        double scale = amplitude * 32_767.0D;
        for (int i = 0; i < length; i++) {
            out[i] = (short) Math.round(Math.sin(2.0 * Math.PI * frequency * i / sampleRate) * scale);
        }
        return out;
    }

    private static double energy(short[] samples) {
        double sum = 0.0D;
        for (short sample : samples) {
            sum += (double) sample * sample;
        }
        return sum / samples.length;
    }

    /**
     * Оцінка домінуючої частоти автокореляцією на другій половині сигналу
     * (перша — прогрів/затримка ланцюжка). Стійкіша за підрахунок
     * нульових переходів, який ламається на гармоніках і хвості реверба.
     */
    private static double dominantFrequency(short[] pcm, int sampleRate, double minHz, double maxHz) {
        int start = pcm.length / 2;
        int minLag = (int) Math.floor(sampleRate / maxHz);
        int maxLag = (int) Math.ceil(sampleRate / minHz);

        double bestCorrelation = -1.0D;
        int bestLag = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            int count = pcm.length - start - lag;
            if (count <= 0) {
                continue;
            }
            double sum = 0.0D;
            for (int i = start; i < start + count; i++) {
                sum += (double) pcm[i] * pcm[i + lag];
            }
            sum /= count;
            if (sum > bestCorrelation) {
                bestCorrelation = sum;
                bestLag = lag;
            }
        }

        if (bestLag <= 0) {
            return 0.0D;
        }
        return (double) sampleRate / bestLag;
    }
}
