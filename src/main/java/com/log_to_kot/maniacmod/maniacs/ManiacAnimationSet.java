package com.log_to_kot.maniacmod.maniacs;

/**
 * Імена анімацій, якими користується клієнтська стейт-машина маньяка.
 *
 * ── Чому саме ЦІ вісім ────────────────────────────────────────────────
 * Це повний набір станів, які взагалі виникають у геймплеї маньяка:
 *   idle        — стоїть
 *   walk        — іде
 *   run         — біжить (у маньяка «біг» = підвищена ходьба)
 *   jump        — у повітрі
 *   sneak       — присідає на місці
 *   sneak_walk  — рухається присідаючи (закладання пастки)
 *   attack      — замах удару (одноразова)
 *   interact    — взаємодія (ставить пастку / використовує предмет)
 *
 * ── Чому короткі імена, а не повні ───────────────────────────────────
 * GeckoLib сам додає префікс {@code animation.<identifier>.} при пошуку в
 * animation-файлі, тому в файлі анімації ключі мають бути
 * {@code animation.test_maniac.idle} і т.д., а тут — лише {@code idle}.
 * Так само зроблено для генератора (див. {@code GeneratorGeoModel}).
 *
 * ── Архетип може перевизначити ───────────────────────────────────────
 * Художник, у якого інший набір анімацій, повертає свій
 * {@link ManiacAnimationSet} з {@link ManiacArchetype#visuals()} — але
 * тоді кожне ім'я має існувати в його animation-файлі, інакше GeckoLib
 * мовчки не програє цю гілку (модель застигне в позі попередньої
 * анімації, помилки в лог може й не бути) — тому краще лишати
 * {@link #defaults()}.
 */
public record ManiacAnimationSet(String idle, String walk, String run, String jump,
                                 String sneak, String sneakWalk, String attack, String interact) {

    /** Стандартні імена — те, що очікує стейт-машина за замовчуванням. */
    public static ManiacAnimationSet defaults() {
        return new ManiacAnimationSet("idle", "walk", "run", "jump",
            "sneak", "sneak_walk", "attack", "interact");
    }
}
