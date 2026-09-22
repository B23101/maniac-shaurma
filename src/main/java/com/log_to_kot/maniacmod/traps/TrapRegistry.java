package com.log_to_kot.maniacmod.traps;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Реєстр усіх пасток гри. Додати нову пастку — один клас-нащадок
 * TrapArchetype + один рядок register(...) тут.
 */
public final class TrapRegistry {

    private static final Map<String, TrapArchetype> BY_ID = new LinkedHashMap<>();

    static {
        register(new BearTrapArchetype());
        // Ще не реалізовані (немає розміщення й тіла у світі) — НЕ реєструємо,
        // інакше вони потрапили б у меню вибору й падали при використанні.
        // Реалізував пастку → розкоментуй її рядок тут:
        // register(new ElectricWireArchetype());
        // register(new RopeBindArchetype());
        // register(new MineArchetype());
    }

    private TrapRegistry() {}

    public static void register(TrapArchetype archetype) {
        BY_ID.put(archetype.id(), archetype);
    }

    public static TrapArchetype get(String id) {
        TrapArchetype found = BY_ID.get(id);
        if (found == null) {
            throw new IllegalArgumentException("Невідома пастка: " + id);
        }
        return found;
    }

    public static Map<String, TrapArchetype> all() {
        return Map.copyOf(BY_ID);
    }
}
