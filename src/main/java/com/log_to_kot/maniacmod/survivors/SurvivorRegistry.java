package com.log_to_kot.maniacmod.survivors;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Реєстр усіх ролей виживого. Зараз лише DEFAULT — але коли
 * з'явиться друга роль (медик, розвідник тощо), додається сюди
 * так само, як маньяк чи пастка.
 *
 * ── Чому defaultRole() будує роль щоразу, а не кешує в static{} ──────
 * DefaultSurvivorRole читає maxHp/inventorySlots/... з ManiacConfigs
 * у своєму конструкторі. Якби реєстрація була одноразовою в static{}
 * (як раніше), роль побудувалась би з тих значень, що діяли в момент
 * ПЕРШОГО звернення до класу — а адмінська правка survivors.yml
 * посеред сесії сервера ніколи не підхопилась би для наступного
 * матчу, бо застарілий об'єкт роль лишався б у мапі назавжди. Це
 * порушило б саме те правило ManiacConfigs, що гетер завжди читає
 * актуальний снапшот (див. коментар класу ManiacConfigs).
 */
public final class SurvivorRegistry {

    private static final Map<String, java.util.function.Supplier<SurvivorRole>> FACTORIES =
        new LinkedHashMap<>();

    static {
        register(DefaultSurvivorRole.ID, DefaultSurvivorRole::new);
    }

    private SurvivorRegistry() {}

    /** Реєструє фабрику ролі за id — роль будується заново при кожному запиті. */
    public static void register(String id, java.util.function.Supplier<SurvivorRole> factory) {
        FACTORIES.put(id, factory);
    }

    public static SurvivorRole get(String id) {
        var factory = FACTORIES.get(id);
        if (factory == null) {
            throw new IllegalArgumentException("Невідома роль виживого: " + id);
        }
        return factory.get();
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

    /** Список id усіх зареєстрованих ролей — для команд/діагностики. */
    public static Map<String, SurvivorRole> all() {
        Map<String, SurvivorRole> snapshot = new LinkedHashMap<>();
        for (var entry : FACTORIES.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().get());
        }
        return Map.copyOf(snapshot);
    }
}
