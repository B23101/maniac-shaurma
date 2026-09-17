package com.log_to_kot.maniacmod.core.match;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;

/**
 * Дебаг-режим для одиночних перевірок: один гравець, без карти, без
 * маньяка — і матч усе одно стартує й не скидається сам.
 *
 * <p>Потрібен саме тому, що перевіряти механіки наодинці інакше
 * неможливо: <code>minPlayers</code> не дає стартувати, порожній реєстр
 * маньяків не дає обрати маньяка, а <code>aliveSurvivorCount() == 0</code>
 * одразу кидає в ENDING (що завжди станеться, якщо ти маньяк і виживих
 * немає взагалі).</p>
 *
 * <p>Окрім конфігу, є runtime-перемикач ({@code toggleRuntime}), який
 * працює без перезапису файлу — для швидкого тесту з клавіатури.
 * {@code enabled()} повертає {@code true}, якщо увімкнено хоча б одне з
 * двох джерел (конфіг або runtime-toggle).</p>
 */
public final class DebugMode {

    /** Runtime-перемикач дебаг-режиму. Діє на клієнті окремо від конфігу. */
    private static boolean runtimeToggle = false;

    /** Увімкнутий дебаг: конфіг АБО runtime-toggle. */
    public static boolean enabled() {
        return runtimeToggle || ManiacConfigs.get(ConfigSchema.DEBUG_MODE);
    }

    /** Перемкнути runtime-дебаг. */
    public static boolean toggleRuntime() {
        runtimeToggle = !runtimeToggle;
        return runtimeToggle;
    }

    /** Поточний стан runtime-toggle (без урахування конфігу). */
    public static boolean isRuntimeToggled() {
        return runtimeToggle;
    }

    /** Ким буде єдиний гравець у дебазі. */
    public enum Role {
        /** Звичайний вибір маньяка (реєстр + конфіг maniac_selection). */
        AUTO,
        /** Ти маньяк; виживих немає взагалі. */
        MANIAC,
        /** Ти виживий; маньяка немає взагалі. */
        SURVIVOR
    }

    private DebugMode() {}

    public static Role role() {
        String raw = ManiacConfigs.get(ConfigSchema.DEBUG_ROLE);
        try {
            return Role.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            // Конфіг уже валідує перелік; це лише захист від null.
            return Role.AUTO;
        }
    }

    /** Чи це режим «без маньяка взагалі» (дебаг + роль SURVIVOR). */
    public static boolean survivorOnly() {
        return enabled() && role() == Role.SURVIVOR;
    }
}
