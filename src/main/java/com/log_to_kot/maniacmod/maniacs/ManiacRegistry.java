package com.log_to_kot.maniacmod.maniacs;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Реєстр маньяків.
 *
 * ── Порожній навмисно ────────────────────────────────────────────────
 * Чакі й Слендермен, описані в v3, прибрані: набір персонажів буде
 * свій. Реєстр лишається порожнім, доки не з'явиться перший архетип —
 * і це нормальний стан, а не помилка збірки.
 *
 * Мод переживає порожній реєстр без падінь: команда старту скаже, що
 * жодного маньяка не зареєстровано, замість NoSuchElementException
 * посеред ініціалізації матчу.
 *
 * ── Додати маньяка ───────────────────────────────────────────────────
 *   1. Клас у цьому пакеті: {@code XxxArchetype extends ManiacArchetype}
 *   2. Рядок {@code register(new XxxArchetype());} у static-блоці
 * Усе інше (вибір персонажа, конфіг FIXED, команда) читає реєстр і
 * підхопить його саме.
 *
 * Межі 3 здібності / 3 пастки перевіряються ТУТ, при реєстрації — тому
 * зайва здібність падає на старті сервера, а не губиться в грі.
 */
public final class ManiacRegistry {

    private static final Map<String, ManiacArchetype> BY_ID = new LinkedHashMap<>();

    static {
        // ТИМЧАСОВО: тестовий маньяк, поки немає справжнього. Замінити
        // справжнім = прибрати цей рядок і додати свій (див. TestManiacArchetype).
        register(new TestManiacArchetype());
        // register(new XxxArchetype());
    }

    private ManiacRegistry() {}

    public static void register(ManiacArchetype archetype) {
        archetype.validate();
        if (BY_ID.putIfAbsent(archetype.id(), archetype) != null) {
            throw new IllegalStateException("Маньяк '" + archetype.id() + "' зареєстрований двічі.");
        }
    }

    public static ManiacArchetype get(String id) {
        ManiacArchetype found = BY_ID.get(id);
        if (found == null) throw new IllegalArgumentException("Невідомий маньяк: " + id);
        return found;
    }

    public static boolean exists(String id) {
        return BY_ID.containsKey(id);
    }

    public static boolean isEmpty() {
        return BY_ID.isEmpty();
    }

    public static Map<String, ManiacArchetype> all() {
        return Map.copyOf(BY_ID);
    }
}
