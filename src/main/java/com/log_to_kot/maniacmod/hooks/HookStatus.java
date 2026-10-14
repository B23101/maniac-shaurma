package com.log_to_kot.maniacmod.hooks;

/**
 * Під-стан непритомного виживого ({@code SurvivorState.UNCONSCIOUS}) у механіці гаків.
 *
 * ── Чому НЕ нове значення SurvivorState ──────────────────────────────────
 * Лежачий, несений і підвішений — усе це «непритомний» для решти гри: удар маньяка по ньому
 * ігнорується, стаміна на нулі, мітка на карті, підняття союзником. Додати три нові значення в
 * {@code SurvivorState} означало б пройтись по кожному {@code == UNCONSCIOUS} у проєкті (їх
 * десятки) і ризикнути забути одне. Тому стан лишається один, а ЯК саме він лежить — окремий
 * маленький enum, який знає лише {@link HookModule}.
 *
 * {@link #code()} йде в {@code DownedSurvivorsPacket} як байт; значення НЕ перенумеровувати —
 * це частина мережевого протоколу.
 */
public enum HookStatus {

    /** Не в механіці гаків (здоровий, спостерігач, маньяк). */
    NONE(0),
    /** Лежить на землі: чекає на союзника або на маньяка. */
    LYING(1),
    /** На плечі маньяка. */
    CARRIED(2),
    /** Висить на гаку. */
    HOOKED(3);

    private final int code;

    HookStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** Чи гравець у цьому статусі їде на невидимому сидінні (плече / гак). */
    public boolean isSeated() {
        return this == CARRIED || this == HOOKED;
    }

    public static HookStatus fromCode(int code) {
        for (HookStatus status : values()) {
            if (status.code == code) return status;
        }
        return NONE;
    }
}
