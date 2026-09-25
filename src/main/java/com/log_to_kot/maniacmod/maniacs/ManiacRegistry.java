package com.log_to_kot.maniacmod.maniacs;

import com.log_to_kot.maniacmod.config.ManiacStats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Реєстр маньяків.
 *
 * ── Додати маньяка ───────────────────────────────────────────────────
 *   1. Клас у цьому пакеті: {@code XxxArchetype extends ManiacArchetype}
 *      (габарити, здібності, пастки, за потреби — дефолти балансу);
 *   2. Асе́ти під id архетипу: {@code geo/entity/<id>.geo.json},
 *      {@code animations/entity/<id>.animation.json},
 *      {@code textures/entity/<id>.png};
 *   3. Рядок {@code register(new XxxArchetype());} у static-блоці.
 * Усе інше робиться саме: вибір персонажа, команди, хітбокс, очі,
 * модель, анімації — і окремий ФАЙЛ КОНФІГУ для цього маньяка
 * ({@code config/maniacmod/maniac_stats/<id>.yml}), який створюється з
 * дефолтів його класу при першому запуску.
 *
 * ── Реєстр порожній — це нормально ──────────────────────────────────
 * Персонажі попередньої версії (Чакі, Слендермен) прибрані: набір буде
 * свій. Порожній реєстр не валить мод — команда старту скаже, що
 * жодного маньяка не зареєстровано, замість NoSuchElementException
 * посеред ініціалізації матчу.
 *
 * ── Що робить register ──────────────────────────────────────────────
 * <ol>
 *   <li>оголошує блок конфігу цього маньяка ({@link ManiacStats}) — тому
 *       реєстрація мусить відбутись ДО {@code ManiacConfigs.init}: перше
 *       ж завантаження конфігу має бачити всі його ключі й створити
 *       файл;</li>
 *   <li>перевіряє межі (3 здібності / 3 пастки / габарити) — зайва
 *       здібність падає на старті сервера, а не губиться в грі;</li>
 *   <li>кладе архетип у реєстр.</li>
 * </ol>
 * Дублікат id — помилка реєстрації, а не «останній переміг».
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

    /**
     * Піднімає реєстр, якщо він ще не піднятий: static-блок вище
     * реєструє архетипи й оголошує їхні блоки конфігу.
     *
     * Потрібно, бо порядок ініціалізації класів у JVM лінивий: без
     * явного виклику {@code ManiacConfigs.init} міг би відпрацювати
     * раніше за цей клас, і перше завантаження конфігу пройшло б по
     * схемі без жодного блока маньяка (файли створились би лише після
     * наступного reload). Викликається з {@code ManiacMod} перед
     * {@code ManiacConfigs.init}.
     */
    public static void ensureLoaded() {
        // Тіло навмисно порожнє: достатньо, щоб JVM виконала static-блок.
    }

    public static void register(ManiacArchetype archetype) {
        ManiacStats.ensureRegistered(archetype);
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
