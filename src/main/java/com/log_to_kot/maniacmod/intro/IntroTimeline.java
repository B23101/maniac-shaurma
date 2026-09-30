package com.log_to_kot.maniacmod.intro;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;

/**
 * ЄДИНА часова шкала вступу — її читають і сервер ({@code IntroModule},
 * {@code MatchOrchestrator}), і клієнт ({@code IntroClient}). Числа лежать в
 * одному місці, щоб «коли чорний екран» і «коли телепорт» ніколи не розходились.
 *
 * <pre>
 *  клієнт, мс від пакета IntroStart (W = вікно Replay, за замовч. 10 000):
 *
 *  0        900     1600      2400                     W        W+900
 *  ├─темніє─┤─чорний─┤─розтемн.─┤──── Replay + loader ───┤─темніє─┤─чорний до IntroReveal─→ розтемнення
 *           ▲ камера            ▲ letterbox                     ▲ телепорт на ~75% затемнення
 *           стартує             з'їжджає                        (сервер)
 *  музика ─┘ (від 0)                                          музика гасне разом із розтемненням
 * </pre>
 *
 * Камера і скрипт Replay стартують у {@link #CAMERA_START_MS} під чорним і
 * мають стелю W, тож рівно в {@code CAMERA_START_MS + W} екран повністю чорний,
 * а Replay припиняється. Loader живе ті самі W мс — рівно два «слоти» порад по 5 с.
 */
public final class IntroTimeline {

    private IntroTimeline() {}

    public static final int DARKEN_IN_MS = 900;
    public static final int PRE_HOLD_MS = 700;
    public static final int REVEAL_MS = 800;
    public static final int DARKEN_OUT_MS = 900;
    /** Фінальне розтемнення разом із початком гри (пробудження). */
    public static final int FADE_IN_MS = 1500;

    /** Коли клієнтська камера стартує (екран уже повністю чорний). */
    public static final int CAMERA_START_MS = DARKEN_IN_MS;

    /**
     * Сервер закінчує CINEMATIC (і телепортує гравців) на цій частці фінального
     * затемнення. Криву затемнення ease-in-out: на 75% часу екран уже ≈94% чорний,
     * тож зникнення чанків Replay нікому не видно.
     */
    public static final float TELEPORT_AT_DARKEN_FRACTION = 0.75f;

    /** Скільки тіків SCATTER тримає чорний екран після телепорту: догрузка чанків + повернення контролю. */
    public static final int SCATTER_HOLD_TICKS = 30;

    /** Додаткові тіки ROLE_REVEAL, які з'їдає повільне розтемнення (щоб пробудження й роль не обрізались). */
    public static final int ROLE_REVEAL_EXTRA_TICKS = FADE_IN_MS / 50;

    // ── Похідні від конфігу ──────────────────────────────────────────────

    public static boolean enabled() {
        return ManiacConfigs.get(ConfigSchema.INTRO_ENABLED);
    }

    public static int windowMs() {
        return ManiacConfigs.get(ConfigSchema.INTRO_REPLAY_SECONDS) * 1000;
    }

    public static int tipIntervalMs() {
        return ManiacConfigs.get(ConfigSchema.INTRO_TIP_SECONDS) * 1000;
    }

    /** Скільки тіків триває CINEMATIC на сервері (0 — вступ вимкнено, фаза пропускається як раніше). */
    public static int cinematicTicks() {
        return enabled() ? fullCinematicTicks() : 0;
    }

    /** Тривалість вступу незалежно від {@code introEnabled} — для прев'ю. */
    public static int fullCinematicTicks() {
        int ms = CAMERA_START_MS + windowMs() - Math.round(DARKEN_OUT_MS * (1f - TELEPORT_AT_DARKEN_FRACTION));
        return (ms + 49) / 50;
    }

    public static int scatterHoldTicks() {
        return enabled() ? SCATTER_HOLD_TICKS : 0;
    }

    public static int roleRevealExtraTicks() {
        return enabled() ? ROLE_REVEAL_EXTRA_TICKS : 0;
    }
}
