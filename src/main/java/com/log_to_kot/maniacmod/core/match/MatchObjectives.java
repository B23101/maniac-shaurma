package com.log_to_kot.maniacmod.core.match;

import java.util.EnumSet;
import java.util.Set;

/**
 * Що гравці вже зробили в цьому матчі.
 *
 * ── Навіщо окремий клас ──────────────────────────────────────────────
 * Фази мають рухатись від ДІЙ гравців, а не від їхньої кількості.
 * «Лишився один виживий» — це не подія матчу: гравці могли вийти,
 * впасти з даху або взагалі не почати грати. Фінал настає тоді, коли
 * команда чогось досягла: живлення подано, ворота відкриті.
 *
 * Тому умови переходів читають ці прапорці, а не рахують гравців.
 * Кількість живих лишається тільки в одній умові — «не лишилось
 * нікого», і це вже не прогрес, а завершення матчу.
 *
 * Прапорці ставлять модулі: генератори — {@link #POWER_RESTORED},
 * виходи — {@link #EXIT_OPENED}. Сам клас нічого не вирішує.
 */
public final class MatchObjectives {

    public enum Objective {
        /** Усі потрібні генератори доведені до кінця (ремонт + бензин). */
        POWER_RESTORED,

        /** Хоча б один вихід повністю відкрито. */
        EXIT_OPENED,

        /** Хоча б один виживий вибрався з карти. */
        SOMEONE_ESCAPED,

        /** Маньяк вивів з гри хоча б одного виживого. */
        FIRST_BLOOD
    }

    private final Set<Objective> completed = EnumSet.noneOf(Objective.class);

    /** Позначає досягнення. Повертає true, якщо це сталось уперше. */
    public boolean complete(Objective objective) {
        return completed.add(objective);
    }

    public boolean isComplete(Objective objective) {
        return completed.contains(objective);
    }

    public Set<Objective> completed() {
        return EnumSet.copyOf(completed);
    }
}
