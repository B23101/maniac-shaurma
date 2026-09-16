package com.log_to_kot.maniacmod.survivors;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Реєстр усіх ролей виживого. Зараз лише DEFAULT — але коли
 * з'явиться друга роль (медик, розвідник тощо), додається сюди
 * так само, як маньяк чи пастка.
 */
public final class SurvivorRegistry {

    private static final Map<String, SurvivorRole> BY_ID = new LinkedHashMap<>();

    static {
        register(new DefaultSurvivorRole());
    }

    private SurvivorRegistry() {}

    public static void register(SurvivorRole role) {
        BY_ID.put(role.id(), role);
    }

    public static SurvivorRole get(String id) {
        SurvivorRole found = BY_ID.get(id);
        if (found == null) {
            throw new IllegalArgumentException("Невідома роль виживого: " + id);
        }
        return found;
    }

    /**
     * Роль, яку отримує гравець, якщо матч не призначає іншої.
     * MatchOrchestrator викликає саме це, а не get("default") —
     * щоб id ролі за замовчуванням не був захардкодженим рядком
     * у кількох місцях.
     */
    public static SurvivorRole defaultRole() {
        return get(DefaultSurvivorRole.ID);
    }

    public static Map<String, SurvivorRole> all() {
        return Map.copyOf(BY_ID);
    }
}
