package com.log_to_kot.maniacmod.voice;

import com.log_to_kot.maniacmod.voice.dsp.ManiacVoiceChain;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;

/**
 * Стан голосового ефекту маньяка на клієнті того, хто говорить.
 *
 * ── Де це живе і чому ────────────────────────────────────────────────
 * Обробка голосу відбувається на клієнті ГРАВЦЯ, ДО відправки на
 * сервер: Simple Voice Chat кличе {@code ClientSoundEvent} у своєму
 * потоці мікрофона, і саме там мод підмінює PCM. Це офіційна, документована
 * точка розширення SVC, не хак. Наслідок: голос страшний ЛИШЕ для тих,
 * хто йде через цей клієнт; клієнт без мода голос не змінює — але це і
 * не потрібно, бо маньяк сам ставить мод.
 *
 * ── «Гра керує голосом, гравець — ні» ────────────────────────────────
 * Тут НЕМАЄ жодного UI, поля чи ручки, яку гравець міг би покрутити:
 * {@link #activate}/{@link #deactivate} викликає виключно
 * {@link VoiceActivation} за роллю й фазою матчу. Сторонній
 * voice-changer, якщо гравець поставить його сам, — це чужий клієнтський
 * мод, поза зоною контролю серверного мода (див. voice/README.md).
 *
 * ── Потоки ───────────────────────────────────────────────────────────
 * {@link #onClientSound} викликається з потоку мікрофона SVC;
 * activate/deactivate — з головного потоку клієнта. Стан переходу —
 * {@code volatile ManiacVoiceProfile profile}: спершу налаштовується й
 * скидається ланцюжок, і лише потім виставляється профіль, тож потік
 * мікрофона ніколи не побачить «напівввімкненого» стану.
 */
public final class ManiacVoiceModule {

    /** Частота дискретизації SVC за замовчуванням. */
    private static final double SAMPLE_RATE = 48_000.0D;

    /** Єдиний екземпляр: SVC-обробник один на клієнт. */
    public static final ManiacVoiceModule INSTANCE = new ManiacVoiceModule();

    private final ManiacVoiceChain chain = new ManiacVoiceChain(SAMPLE_RATE);

    /** null — ефект вимкнено (гравець не маньяк або фаза не голосова). */
    private volatile ManiacVoiceProfile profile = null;

    private ManiacVoiceModule() {}

    /** Чи зараз голос обробляється. */
    public boolean isActive() {
        return profile != null;
    }

    /**
     * Вмикає ефект із заданим профілем. Скидання ланцюжка обов'язкове:
     * без нього між матчами лишався б «хвіст» стану фільтрів (delay-лінії
     * реверба, phase accumulator у pitch shifter'і).
     */
    public synchronized void activate(ManiacVoiceProfile newProfile) {
        this.chain.reset();
        this.chain.configure(newProfile);
        this.profile = newProfile;
    }

    /** Вимикає ефект. Голос далі звучить незміненим. */
    public synchronized void deactivate() {
        this.profile = null;
        this.chain.reset();
    }

    /**
     * Точка входу SVC. Викликається ДО Opus-кодування й відправки.
     * Порожній кадр (кінець передачі) не чіпаємо.
     */
    public void onClientSound(ClientSoundEvent event) {
        ManiacVoiceProfile current = this.profile;
        if (current == null) {
            return;
        }
        short[] raw = event.getRawAudio();
        if (raw == null || raw.length == 0) {
            return;
        }
        short[] processed = raw.clone();
        this.chain.process(processed);
        event.setRawAudio(processed);
    }
}
