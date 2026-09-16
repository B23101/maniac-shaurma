package com.log_to_kot.maniacmod.core.phase;

/**
 * Статичний фасад над активним PhaseManager — щоб предмет, блок або
 * пакет міг спитати про фазу одним рядком, не тягнучи посилання на
 * весь матч через півмоду:
 *
 *     if (!Phases.allows(PhaseRule.GENERATOR_REPAIR)) return InteractionResult.FAIL;
 *
 * ВАЖЛИВО: це ЄДИНИЙ статичний стан, який лишається в новій
 * архітектурі. Усе інше (маньяк, виживші, карта, пастки) живе
 * всередині MatchContext як звичайні поля об'єкта — саме тому
 * старий ManiacGameManager з двома десятками статичних списків
 * і розпадався: два матчі/перезапуск світу лишали сміття в статиці.
 *
 * Тут статика безпечна, бо це лише ОДНЕ посилання, яке
 * MatchOrchestrator встановлює на старті сервера й знімає на зупинці.
 */
public final class Phases {

    private static volatile PhaseManager active;

    private Phases() {}

    /** Викликається один раз з MatchOrchestrator при створенні. */
    public static void bind(PhaseManager manager) {
        active = manager;
    }

    /** Викликається при зупинці сервера, щоб не лишати висяче посилання. */
    public static void unbind() {
        active = null;
    }

    public static PhaseManager manager() {
        PhaseManager m = active;
        if (m == null) throw new IllegalStateException(
            "PhaseManager не прив'язаний — Phases.bind() викликається з MatchOrchestrator.");
        return m;
    }

    // ── Короткі запитання, які використовує решта мода ────────────────────

    /** Поточна фаза; LOBBY якщо менеджер ще не прив'язаний (клієнт до входу). */
    public static GamePhase current() {
        PhaseManager m = active;
        return m == null ? GamePhase.LOBBY : m.current();
    }

    /** Чи матч зараз реально йде (будь-яка GAMEPLAY-фаза). */
    public static boolean isGameplay() {
        return current().isGameplay();
    }

    /** Чи фаза дозволяє конкретну дію. Основний метод для всього мода. */
    public static boolean allows(PhaseRule rule) {
        return current().allows(rule);
    }

    public static boolean is(GamePhase phase) {
        return current() == phase;
    }
}
