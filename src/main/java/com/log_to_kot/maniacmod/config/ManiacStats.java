package com.log_to_kot.maniacmod.config;

import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * Параметри конкретного маньяка — окремий блок конфігу на КОЖНОГО маньяка.
 *
 * ── Навіщо це окремо від {@link ConfigSchema} ────────────────────────
 * Схема статична: її ключі можна перелічити в одному файлі, бо вони
 * однакові для будь-якого запуску. Параметри маньяка такими бути не
 * можуть: скільки маньяків — стільки наборів, і склад кожного визначає
 * КЛАС архетипу (див. {@code ManiacRegistry}). Тому блок оголошує не
 * схема, а сам архетип, у момент реєстрації:
 *
 * <pre>
 *   config/maniacmod/maniac_stats/&lt;id&gt;.yml
 *
 *   hitboxWidth: 1.2      hitboxHeight: 3.0   eyeHeight: 2.7
 *   attackRangeBlocks: 1.0    attackCooldownTicks: 120
 *   attackDamage: 50      speedMultiplier: 1.2
 * </pre>
 *
 * Далі з таким блоком працює ВСЕ те саме, що й зі статичними: валідація
 * діапазонів, лікування файла, діагностика мертвих ключів, гаряче
 * перезавантаження, меню налаштувань. Новий код для цього не потрібен —
 * блок будується тими самими фабриками {@link ConfigKey}.
 *
 * ── Два джерела дефолта, і чому саме так ─────────────────────────────
 * <ol>
 *   <li><b>Код архетипу</b> — {@code declaredXxx()} у
 *       {@link ManiacArchetype}. Це характер персонажа: триблочний
 *       маньяк оголошує свої 3.0, швидкий — свій множник. Саме це число
 *       потрапляє в новий yml як стартове.</li>
 *   <li><b>Файл</b> — те, що адмін написав у ньому. Перекриває код
 *       завжди й без перекомпіляції.</li>
 * </ol>
 * Спільного ключа «на всіх маньяків» тут немає навмисно: він змушував би
 * правити один рядок, щоб змінити ОДНОГО маньяка, а різні маньяки різні
 * за всіма цими числами за дизайном. Числа, справді спільні для режиму
 * (перезарядка капкана, дальність розміщення пасток), лишились у блоці
 * {@code maniac} — вони не залежать від того, ХТО полює.
 *
 * ── Чому читання йде через цей клас, а не через {@code ManiacConfigs} ─
 * Щоб гетер архетипу не знав про конфіг узагалі: {@code hitboxWidth()}
 * лишається виразом «яка висота в цього маньяка ЗАРАЗ», а де взято
 * число — конфіг чи код — вирішується тут, в одному місці.
 */
public final class ManiacStats {

    /** Префікс id блока: {@code maniac_stats_<id маньяка>}. */
    public static final String BLOCK_PREFIX = "maniac_stats_";

    /** Підтека конфігу, у якій лежить файл кожного маньяка. */
    public static final String DIRECTORY = "maniac_stats";

    // ── Імена ключів ─────────────────────────────────────────────────────
    // Константи, а не рядки «десь у коді»: на них зав'язані і гетер
    // архетипу, і спільні переклади в меню налаштувань.

    public static final String HITBOX_WIDTH = "hitboxWidth";
    public static final String HITBOX_HEIGHT = "hitboxHeight";
    public static final String EYE_HEIGHT = "eyeHeight";
    public static final String ATTACK_RANGE = "attackRangeBlocks";
    public static final String ATTACK_COOLDOWN = "attackCooldownTicks";
    public static final String ATTACK_DAMAGE = "attackDamage";
    public static final String SPEED = "speedMultiplier";

    /**
     * Числовий ключ: ім'я, дозволений діапазон і дефолт із КОДУ архетипу.
     *
     * Діапазон тут — не «на око»: він мусить вміщати дефолти всіх
     * архетипів. Архетип, що оголосить число поза ним, падає на
     * реєстрації з людським текстом (див. {@link #decimalKey}), а не
     * отримує мовчки затиснуте значення — затиснути можна лише те, що
     * адмін написав у файлі, а не те, що автор маньяка заклав у код.
     */
    private record Decimal(String name, double min, double max, ToDoubleFunction<ManiacArchetype> declared) {}

    /** Цілочисловий ключ: те саме, але без дробової частини. */
    private record Whole(String name, int min, int max, ToIntFunction<ManiacArchetype> declared) {}

    private static final List<Decimal> DECIMALS = List.of(
        new Decimal(HITBOX_WIDTH,   0.2,  8.0, ManiacArchetype::declaredHitboxWidth),
        new Decimal(HITBOX_HEIGHT,  0.5, 12.0, ManiacArchetype::declaredHitboxHeight),
        new Decimal(EYE_HEIGHT,     0.1, 12.0, ManiacArchetype::declaredEyeHeight),
        new Decimal(ATTACK_RANGE,   0.5,  8.0, ManiacArchetype::declaredAttackRangeBlocks),
        new Decimal(SPEED,          0.1,  5.0, ManiacArchetype::declaredSpeedMultiplier));

    private static final List<Whole> WHOLES = List.of(
        new Whole(ATTACK_COOLDOWN, 10,  600, ManiacArchetype::declaredAttackCooldownTicks),
        new Whole(ATTACK_DAMAGE,    1, 1000, ManiacArchetype::declaredAttackDamage));

    /** id маньяка → ім'я ключа → ключ. Наповнюється одноразово, при реєстрації. */
    private static final Map<String, Map<String, ConfigKey<?>>> KEYS = new LinkedHashMap<>();

    private ManiacStats() {}

    // ── Реєстрація ───────────────────────────────────────────────────────

    /**
     * Оголошує блок конфігу для маньяка й запам'ятовує його ключі.
     *
     * Викликається рівно один раз на архетип — з
     * {@code ManiacRegistry.register}, тобто до {@code ManiacConfigs.init}.
     * Повторний виклик для того самого id — не помилка, а нормальний
     * no-op: реєстр і сам не приймає дублікат id, але покладатися на це
     * в порядку ініціалізації не варто.
     */
    public static synchronized void ensureRegistered(ManiacArchetype archetype) {
        if (KEYS.containsKey(archetype.id())) return;

        String blockId = BLOCK_PREFIX + archetype.id();
        Map<String, ConfigKey<?>> keys = new LinkedHashMap<>();
        for (Decimal spec : DECIMALS) keys.put(spec.name(), decimalKey(blockId, spec, archetype));
        for (Whole spec : WHOLES) keys.put(spec.name(), wholeKey(blockId, spec, archetype));

        // Перше речення коментаря — назва таба в меню налаштувань
        // (перекладу за id тут бути не може, він динамічний), тому далі
        // йде пояснення, а не продовження назви.
        String comment = archetype.displayName()
            + ". Габарити, бій і швидкість саме цього маньяка (id "
            + archetype.id() + "). Файл створюється автоматично.";

        ConfigSchema.registerManiacBlock(ConfigBlock.settings(
            blockId, DIRECTORY + "/" + archetype.id() + ".yml", comment,
            keys.values().toArray(new ConfigKey<?>[0])));

        KEYS.put(archetype.id(), keys);
    }

    private static ConfigKey<Double> decimalKey(String blockId, Decimal spec, ManiacArchetype archetype) {
        double def = spec.declared().applyAsDouble(archetype);
        requireWithin(archetype, spec.name(), def, spec.min(), spec.max());
        return ConfigKey.decimal(blockId, spec.name(), def, spec.min(), spec.max());
    }

    private static ConfigKey<Integer> wholeKey(String blockId, Whole spec, ManiacArchetype archetype) {
        int def = spec.declared().applyAsInt(archetype);
        requireWithin(archetype, spec.name(), def, spec.min(), spec.max());
        return ConfigKey.integer(blockId, spec.name(), def, spec.min(), spec.max());
    }

    /**
     * Межі ключа мусять вміщати те, що оголосив автор маньяка: інакше
     * або дефолт у файлі був би затиснутим (маньяк у грі не такий, як
     * написано в його ж коді), або ключ довелось би розширювати щоразу,
     * як з'являється новий персонаж. Тому це падіння на реєстрації.
     */
    private static void requireWithin(ManiacArchetype archetype, String name,
                                      double value, double min, double max) {
        if (value < min || value > max) {
            throw new IllegalStateException("Маньяк '" + archetype.id() + "': "
                + name + " = " + value + " не влізає в діапазон конфігу "
                + min + "…" + max + ". Розшир діапазон у ManiacStats або поправ оголошений дефолт.");
        }
    }

    // ── Читання ──────────────────────────────────────────────────────────

    /**
     * Значення цього маньяка ПРОСТО ЗАРАЗ: із конфігу, якщо ключ уже
     * зареєстрований, інакше — оголошений у коді дефолт.
     *
     * Другий випадок не «мертвий код»: архетип, який ніхто не
     * зареєстрував (напр. локальна змінна в тесті), не має блока, а
     * гетер мусить відповісти числом, а не {@code null}.
     */
    public static Number value(ManiacArchetype archetype, String name) {
        ConfigKey<?> key = key(archetype.id(), name);
        if (key != null) return (Number) ManiacConfigs.get(key);
        return declared(archetype, name);
    }

    private static ConfigKey<?> key(String maniacId, String name) {
        Map<String, ConfigKey<?>> keys = KEYS.get(maniacId);
        return keys == null ? null : keys.get(name);
    }

    private static Number declared(ManiacArchetype archetype, String name) {
        for (Decimal spec : DECIMALS) {
            if (spec.name().equals(name)) return spec.declared().applyAsDouble(archetype);
        }
        for (Whole spec : WHOLES) {
            if (spec.name().equals(name)) return spec.declared().applyAsInt(archetype);
        }
        throw new IllegalArgumentException("Невідомий параметр маньяка: " + name);
    }

    /**
     * Імена всіх параметрів, які має маньяк, — у порядку оголошення вище.
     * Потрібні лише для повідомлень про помилки (щоб список був один, а
     * не переписаний у тексті).
     */
    public static List<String> names() {
        List<String> all = new ArrayList<>();
        for (Decimal spec : DECIMALS) all.add(spec.name());
        for (Whole spec : WHOLES) all.add(spec.name());
        return List.copyOf(all);
    }
}
