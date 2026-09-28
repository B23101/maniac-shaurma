package com.log_to_kot.maniacmod.voice;

/**
 * Параметри «жахливого» голосу маньяка — повний набір чисел одного
 * пресету. За принципом мода «баланс окремо від логіки» це ЗВИЧАЙНИЙ
 * незмінний об'єкт, а не набір констант, розкиданих по коду ланцюжка:
 * щоб додати другий голос (TITAN, BATMAN), досить оголосити ще одну
 * константу тут — {@link ManiacVoiceChain} не змінюється взагалі.
 *
 * ── Звідки числа ─────────────────────────────────────────────────────
 * Це значення пресету DEMON з sv-voice-changer (автор sawiq_, 2026),
 * ліцензія CC BY-NC-SA 4.0 — див. {@code voice/dsp/LICENSE-DSP.txt}.
 * Коментарі до кожного поля — з авторського опису пресету.
 *
 * ── Чому це record ───────────────────────────────────────────────────
 * Читається зі звукового потоку (не UI) і ніколи не змінюється на місці:
 * значення перемикається цілком (activate певного профілю), тому
 * часткові сеттери тут зайві й небезпечні.
 */
public record ManiacVoiceProfile(
        /** Сухий/вологий баланс: 1.0 — повністю оброблений голос. */
        double mix,
        /** Підсумковий підсилювач після всього ланцюжка. */
        double gain,
        /** Висота тону. 0.68 — на ~32% нижче (не рівно октава, щоб не звучати мультяшно). */
        double pitch,
        /** Форманти ОКРЕМО від pitch: зберігає відчуття «людини», просто більшої. */
        double formant,
        /** Підмішування нижньої октави (грудний шар / growl). */
        double growl,
        /** Низькі частоти, дБ. */
        double lowEqDb,
        /** Середина, дБ. */
        double midEqDb,
        /** Високі, дБ. */
        double highEqDb,
        /** М'яке насичення (waveshaping, не жорсткий clip). */
        double distortion,
        /** Частка ревербератора в сигналі. */
        double reverbMix,
        /** Розмір простору: 0 — кабіна, 1 — зал. */
        double reverbSize,
        /** Час загасання хвоста: 0 — короткий ляпас, 1 — довгий. */
        double reverbDecay,
        /** Шумозаглушувач між фразами. */
        double gate,
        /** Де-есер: прибирає надлишок шиплячих. */
        double deEss) {

    /**
     * Пресет DEMON з sv-voice-changer: «одержимий — низький, скрегітливий,
     * з кафедральним простором позаду».
     */
    public static final ManiacVoiceProfile DEMON = new ManiacVoiceProfile(
            1.00D, 1.12D,
            0.68D, 0.76D, 0.50D,
            2.0D, 0.5D, -3.0D,
            0.26D,
            0.18D, 0.62D, 0.68D,
            0.32D, 0.20D);

    /**
     * Пресет TITAN: «велетенський, повільний, розміром із зал». Формант
     * набагато нижче pitch, щоб продати масштаб.
     */
    public static final ManiacVoiceProfile TITAN = new ManiacVoiceProfile(
            1.00D, 1.10D,
            0.72D, 0.62D, 0.45D,
            1.5D, 0.0D, -2.5D,
            0.06D,
            0.16D, 0.85D, 0.70D,
            0.30D, 0.15D);

    /**
     * Пресет BATMAN: «на октаву нижче, грудний резонанс, хрип, тісна
     * кімната — каптур, а не кафедрал».
     */
    public static final ManiacVoiceProfile BATMAN = new ManiacVoiceProfile(
            1.00D, 1.10D,
            0.80D, 0.80D, 0.55D,
            1.2D, 0.0D, -1.5D,
            0.18D,
            0.10D, 0.28D, 0.40D,
            0.28D, 0.18D);

    /** Нейтральний профіль — те, у що сходиться {@link #scaled(double)} при strength 0. */
    public static final ManiacVoiceProfile NEUTRAL = new ManiacVoiceProfile(
            1.00D, 1.00D,
            1.00D, 1.00D, 0.00D,
            0.0D, 0.0D, 0.0D,
            0.00D,
            0.00D, 0.50D, 0.50D,
            0.00D, 0.00D);

    /**
     * Профіль, наближений до нейтрального на {@code amount} (0..1).
     * Використовується конфіг-силою ефекту: {@code 0} — голос не
     * змінюється взагалі, {@code 1} — повний пресет.
     */
    public ManiacVoiceProfile scaled(double amount) {
        double t = Math.max(0.0D, Math.min(1.0D, amount));
        if (t >= 1.0D) {
            return this;
        }
        return new ManiacVoiceProfile(
                lerp(NEUTRAL.mix, mix, t),
                lerp(NEUTRAL.gain, gain, t),
                lerp(NEUTRAL.pitch, pitch, t),
                lerp(NEUTRAL.formant, formant, t),
                lerp(NEUTRAL.growl, growl, t),
                lerp(NEUTRAL.lowEqDb, lowEqDb, t),
                lerp(NEUTRAL.midEqDb, midEqDb, t),
                lerp(NEUTRAL.highEqDb, highEqDb, t),
                lerp(NEUTRAL.distortion, distortion, t),
                lerp(NEUTRAL.reverbMix, reverbMix, t),
                lerp(NEUTRAL.reverbSize, reverbSize, t),
                lerp(NEUTRAL.reverbDecay, reverbDecay, t),
                lerp(NEUTRAL.gate, gate, t),
                lerp(NEUTRAL.deEss, deEss, t));
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }
}
