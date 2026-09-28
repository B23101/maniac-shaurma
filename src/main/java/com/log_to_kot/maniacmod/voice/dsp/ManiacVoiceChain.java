package com.log_to_kot.maniacmod.voice.dsp;

import com.log_to_kot.maniacmod.voice.ManiacVoiceProfile;

import java.util.Arrays;

/**
 * Спрощений оркестратор DSP-ланцюжка голосу маньяка.
 *
 * ── Що це ────────────────────────────────────────────────────────────
 * Порт ідеї {@code VoiceChannelChain} з sv-voice-changer (CC BY-NC-SA 4.0,
 * див. {@code voice/dsp/LICENSE-DSP.txt}), але БЕЗ гілок, які maniacmod не
 * використовує: autotune, speaker calibration, robot-модуляція, bit-crush,
 * carrier-hiss і tremolo видалені. Лишився рівно той ланцюжок, який
 * описаний у плані (розділ 4.4):
 *
 * <pre>
 *   NoiseGate
 *     → rumble high-pass (75 Гц)
 *     → VocalPitchShifter (pitch/формант ОКРЕМО)
 *     → DeEsser
 *     → ToneStack (low/mid/high EQ)
 *     → SubOctaveGenerator (growl)
 *     → waveshaping distortion
 *     → RoomReverb (crossfade, не додавання)
 *     → output gain
 *     → blend сухого/вологого
 *     → SoftLimiter
 * </pre>
 *
 * ── Сумісність із потоком SVC ────────────────────────────────────────
 * {@link #process(short[])} — зручний вхід для 16-бітного PCM, який дає
 * {@code ClientSoundEvent.getRawAudio()}: перетворення short→float, обробка
 * покадрово (ланцюжок сам тримає стан між кадрами) і clamp назад у short.
 *
 * ── Стан і потоки ────────────────────────────────────────────────────
 * Обробка йде у потоці мікрофона SVC, а activate/deactivate — з головного
 * потоку клієнта. Тому {@link #configure} і {@link #reset} синхронізовані
 * на цьому об'єкті, а гарячий шлях {@link #process(short[])} — ні (він і
 * так викликається лише з одного потоку).
 */
public final class ManiacVoiceChain {

    static final int FFT_SIZE = 1_024;
    /**
     * Вісім кадрів перекриття на вікно: стандартний мінімум для фазового
     * вокодера — чотири, і він лишає на мовленні чутну шорсткість. Подвоєння
     * перекриття коштує FFT, а не затримки (її задає довжина вікна).
     */
    static final int HOP_SIZE = 128;
    /** Затримка ланцюжка в семплах (~21 мс при 48 кГц). */
    static final int LATENCY_SAMPLES = FFT_SIZE - 1;

    private static final double RUMBLE_HZ = 75.0D;
    private static final double PARAMETER_RAMP_SECONDS = 0.03D;
    private static final double SHIFTER_FADE_SECONDS = 0.02D;
    private static final int OUTPUT_QUEUE_SIZE = FFT_SIZE * 2;
    /** Максимальний drive для waveshaping. */
    private static final double MAX_DRIVE = 12.0D;
    /** Скільки енергії growl додає поверх входу. */
    private static final double SUB_OCTAVE_LEVEL = 0.55D;

    private final NoiseGate gate;
    private final Biquad rumbleFilter = new Biquad();
    private final VocalPitchShifter shifter;
    private final DeEsser deEsser;
    private final ToneStack tone;
    private final SubOctaveGenerator subOctave;
    private final RoomReverb reverb;
    private final SoftLimiter limiter;

    private final SmoothedValue blend;
    private final SmoothedValue outputGain;
    private final SmoothedValue reverbMix;
    private final SmoothedValue growlAmount;
    private final SmoothedValue distortion;

    private final float[] inputRing = new float[FFT_SIZE];
    private final float[] analysisWindow = new float[FFT_SIZE];
    private final float[] hopOutput = new float[HOP_SIZE];
    private final float[] outputQueue = new float[OUTPUT_QUEUE_SIZE];
    private final float[] dryDelay = new float[LATENCY_SAMPLES];

    private int inputWriteIndex;
    private long samplesSeen;
    private int samplesSinceHop;
    private int queueReadIndex;
    private int queueWriteIndex;
    private int queuedSamples;
    private int dryDelayIndex;
    private boolean reverbRunning;

    private double pitchRatio = 1.0D;
    private double formantRatio = 1.0D;

    public ManiacVoiceChain(double sampleRate) {
        this.gate = new NoiseGate(sampleRate);
        this.shifter = new VocalPitchShifter((float) sampleRate, FFT_SIZE, HOP_SIZE);
        this.deEsser = new DeEsser(sampleRate);
        this.tone = new ToneStack(sampleRate);
        this.subOctave = new SubOctaveGenerator(sampleRate);
        this.reverb = new RoomReverb(sampleRate);
        this.limiter = new SoftLimiter(sampleRate);

        this.blend = new SmoothedValue(sampleRate, PARAMETER_RAMP_SECONDS, 0.0D);
        this.outputGain = new SmoothedValue(sampleRate, PARAMETER_RAMP_SECONDS, 1.0D);
        this.reverbMix = new SmoothedValue(sampleRate, PARAMETER_RAMP_SECONDS, 0.0D);
        this.growlAmount = new SmoothedValue(sampleRate, PARAMETER_RAMP_SECONDS, 0.0D);
        this.distortion = new SmoothedValue(sampleRate, PARAMETER_RAMP_SECONDS, 0.0D);

        this.rumbleFilter.setHighPass(sampleRate, RUMBLE_HZ, 0.707D);
        configure(ManiacVoiceProfile.NEUTRAL);
    }

    /**
     * Застосовує знімок параметрів. Дешево — можна викликати раз на
     * звуковий блок, як робить {@link #process(short[])} через
     * {@code ManiacVoiceModule}.
     */
    public synchronized void configure(ManiacVoiceProfile profile) {
        this.gate.setAmount(profile.gate());
        this.deEsser.setAmount(profile.deEss());
        this.tone.setEqualiser(profile.lowEqDb(), profile.midEqDb(), profile.highEqDb());
        this.reverb.setRoom(profile.reverbSize(), profile.reverbDecay());
        this.blend.setTarget(clamp(profile.mix(), 0.0D, 1.0D));
        this.outputGain.setTarget(clamp(profile.gain(), 0.0D, 4.0D));
        this.reverbMix.setTarget(clamp(profile.reverbMix(), 0.0D, 1.0D));
        this.growlAmount.setTarget(clamp(profile.growl(), 0.0D, 1.0D));
        this.distortion.setTarget(clamp(profile.distortion(), 0.0D, 1.0D));
        this.pitchRatio = clamp(profile.pitch(), 0.25D, 4.0D);
        this.formantRatio = clamp(profile.formant(), 0.25D, 4.0D);
    }

    /** Профіль, застосований останнім (для діагностики/тестів). */
    public synchronized double appliedPitchRatio() {
        return this.pitchRatio;
    }

    /**
     * Обробляє блок 16-бітного PCM на місці.
     *
     * <p>{@code synchronized} — щоб {@link #reset()} із головного потоку
     * клієнта не могла пройтися по буферах, поки потік мікрофона SVC
     * тримає в них стан. Критична секція коротка (один кадр ~20 мс),
     * тож конкуренція тут нешкідлива.</p>
     */
    public synchronized void process(short[] pcm) {
        for (int i = 0; i < pcm.length; i++) {
            double output = processSample(pcm[i] / 32_768.0D);
            pcm[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE,
                    Math.round(output * 32_767.0D)));
        }
    }

    /** Очищає всі фільтри, лінії затримки й фазу. Виклик при старті потоку. */
    public synchronized void reset() {
        this.gate.reset();
        this.rumbleFilter.reset();
        this.shifter.reset();
        this.deEsser.reset();
        this.tone.reset();
        this.subOctave.reset();
        this.reverb.reset();
        this.limiter.reset();

        Arrays.fill(this.inputRing, 0f);
        Arrays.fill(this.analysisWindow, 0f);
        Arrays.fill(this.outputQueue, 0f);
        Arrays.fill(this.dryDelay, 0f);

        this.inputWriteIndex = 0;
        this.samplesSeen = 0L;
        this.samplesSinceHop = 0;
        this.queueReadIndex = 0;
        this.queueWriteIndex = 0;
        this.queuedSamples = 0;
        this.dryDelayIndex = 0;
        this.reverbRunning = false;

        this.blend.reset(0.0D);
        this.outputGain.reset(1.0D);
        this.reverbMix.reset(0.0D);
        this.growlAmount.reset(0.0D);
        this.distortion.reset(0.0D);
    }

    private double processSample(double input) {
        double cleaned = this.rumbleFilter.process(this.gate.process(input));

        double dry = pushDry(cleaned);
        double wet = advanceShifter(cleaned, dry);

        wet = this.deEsser.process(wet);
        wet = this.tone.process(wet);
        wet = addGrowl(wet);
        wet = drive(wet);
        wet = applyReverb(wet);
        wet *= this.outputGain.next();

        double blended = dry + (wet - dry) * this.blend.next();
        return this.limiter.process(blended);
    }

    /** Додає нижню октаву (грудний шар). */
    private double addGrowl(double input) {
        double amount = this.growlAmount.next();
        if (amount < 0.001D) {
            return input;
        }
        return input + this.subOctave.process(input) * amount * SUB_OCTAVE_LEVEL;
    }

    /**
     * М'яке насичення. Нормування на сам drive (не на {@code tanh(drive)}):
     * останній майже дорівнює 1 при будь-якому корисному значенні, тож не
     * давав жодної компенсації й перетворював ручку drive на другу
     * гучність.
     */
    private double drive(double input) {
        double amount = this.distortion.next();
        if (amount < 0.001D) {
            return input;
        }
        double gain = 1.0D + amount * MAX_DRIVE;
        double saturated = Math.tanh(input * gain) / gain;
        return input + (saturated - input) * Math.min(1.0D, amount * 2.0D);
    }

    /**
     * Кроссфейд хвоста реверба проти прямого звуку, а не додавання поверх:
     * додавання робить кожен поворот ручки ще й гучнішим.
     */
    private double applyReverb(double input) {
        double amount = this.reverbMix.next();
        if (amount <= 0.001D) {
            if (this.reverbRunning) {
                this.reverb.reset();
                this.reverbRunning = false;
            }
            return input;
        }
        this.reverbRunning = true;
        double tail = this.reverb.process(input);
        return input * (1.0D - amount) + tail * amount;
    }

    private double pushDry(double sample) {
        double delayed = this.dryDelay[this.dryDelayIndex];
        this.dryDelay[this.dryDelayIndex] = (float) sample;
        this.dryDelayIndex = this.dryDelayIndex + 1 >= this.dryDelay.length ? 0 : this.dryDelayIndex + 1;
        return delayed;
    }

    /**
     * Одне семпло через STFT-конвеєр. Доти, доки не прийшло повне вікно,
     * повертати нічого — стоїть затриманий сухий сигнал, і вихід
     * лишається неперервним.
     */
    private double advanceShifter(double input, double delayedDry) {
        this.inputRing[this.inputWriteIndex] = (float) input;
        this.inputWriteIndex = this.inputWriteIndex + 1 >= FFT_SIZE ? 0 : this.inputWriteIndex + 1;
        this.samplesSeen++;

        if (this.samplesSeen == FFT_SIZE) {
            runHop();
            this.samplesSinceHop = 0;
        } else if (this.samplesSeen > FFT_SIZE && ++this.samplesSinceHop >= HOP_SIZE) {
            this.samplesSinceHop = 0;
            runHop();
        }

        if (this.queuedSamples == 0) {
            return delayedDry;
        }

        float wet = this.outputQueue[this.queueReadIndex];
        this.queueReadIndex = this.queueReadIndex + 1 >= OUTPUT_QUEUE_SIZE ? 0 : this.queueReadIndex + 1;
        this.queuedSamples--;

        return delayedDry + (wet - delayedDry) * shifterFade();
    }

    private double shifterFade() {
        // Ефект вмикається лише коли гравець маньяк — окремого фейду між
        // сухим і вологим тут не потрібно, тому що activate()/deactivate()
        // скидають стан ланцюжка цілком (без клику посеред фрази).
        return 1.0D;
    }

    private void runHop() {
        flattenInputRing();
        this.shifter.setPitchRatio((float) this.pitchRatio);
        this.shifter.setFormantRatio((float) this.formantRatio);
        this.shifter.process(this.analysisWindow, this.hopOutput);

        for (int i = 0; i < HOP_SIZE; i++) {
            this.outputQueue[this.queueWriteIndex] = this.hopOutput[i];
            this.queueWriteIndex = this.queueWriteIndex + 1 >= OUTPUT_QUEUE_SIZE ? 0 : this.queueWriteIndex + 1;
        }
        this.queuedSamples = Math.min(OUTPUT_QUEUE_SIZE, this.queuedSamples + HOP_SIZE);
    }

    /** Копіює кільцевий буфер у пласке вікно від найстарішого до найновішого. */
    private void flattenInputRing() {
        int firstChunk = FFT_SIZE - this.inputWriteIndex;
        System.arraycopy(this.inputRing, this.inputWriteIndex, this.analysisWindow, 0, firstChunk);
        if (firstChunk < FFT_SIZE) {
            System.arraycopy(this.inputRing, 0, this.analysisWindow, firstChunk, FFT_SIZE - firstChunk);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
