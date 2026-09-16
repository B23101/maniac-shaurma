package com.log_to_kot.maniacmod.config;

import java.util.List;
import java.util.function.Function;

/**
 * Один ключ конфігу: шлях, тип, дефолт і правила допустимих значень.
 *
 * ── Навіщо ключ — об'єкт, а не рядок ─────────────────────────────────
 * Читання йде через {@code ManiacConfigs.get(ConfigSchema.X)}. Тобто
 * прочитати можна ЛИШЕ те, що оголошено в схемі, а все, що оголошено —
 * компілятор бачить. Звідси два наслідки:
 *   • «мертвих» налаштувань не буває: ключ без жодного use-site
 *     видно в IDE як невикористаний символ;
 *   • «неможливих» значень не буває: діапазон/перелік оголошені тут
 *     же, поруч із дефолтом, і перевіряються при кожному читанні
 *     файлу, а не десь у логіці через півмоду.
 *
 * @param <T> тип значення
 */
public final class ConfigKey<T> {

    /** Що робити зі значенням поза допустимим. */
    public enum OnInvalid {
        /** Підтягнути до найближчої межі (числа). */
        CLAMP,
        /** Відкотити до дефолту (переліки, рядки). */
        FALLBACK
    }

    private final String block;
    private final String name;
    private final T defaultValue;
    private final Function<Object, T> parser;
    private final Function<T, T> corrector;
    private final OnInvalid onInvalid;
    private final String describeRange;

    private ConfigKey(String block, String name, T defaultValue,
                      Function<Object, T> parser, Function<T, T> corrector,
                      OnInvalid onInvalid, String describeRange) {
        this.block = block;
        this.name = name;
        this.defaultValue = defaultValue;
        this.parser = parser;
        this.corrector = corrector;
        this.onInvalid = onInvalid;
        this.describeRange = describeRange;
    }

    public String block()          { return block; }
    public String name()           { return name; }
    public String path()           { return block + "." + name; }
    public T defaultValue()        { return defaultValue; }
    public OnInvalid onInvalid()   { return onInvalid; }
    public String describeRange()  { return describeRange; }

    /**
     * Перетворює сире значення з YAML у типізоване й виправляє
     * недопустиме. null (ключа немає) → дефолт.
     */
    public T resolve(Object raw, ConfigDiagnostics diag) {
        if (raw == null) {
            diag.missingKey(this);
            return defaultValue;
        }
        T parsed;
        try {
            parsed = parser.apply(raw);
        } catch (RuntimeException e) {
            diag.wrongType(this, raw);
            return defaultValue;
        }
        T corrected = corrector.apply(parsed);
        if (!corrected.equals(parsed)) {
            diag.corrected(this, parsed, corrected);
        }
        return corrected;
    }

    // ── Фабрики ──────────────────────────────────────────────────────────

    public static ConfigKey<Integer> integer(String block, String name, int def, int min, int max) {
        requireWithin(name, def, min, max);
        return new ConfigKey<>(block, name, def,
            raw -> ((Number) raw).intValue(),
            value -> Math.max(min, Math.min(max, value)),
            OnInvalid.CLAMP, min + "…" + max);
    }

    public static ConfigKey<Double> decimal(String block, String name, double def, double min, double max) {
        if (def < min || def > max) {
            throw new IllegalArgumentException("Дефолт '" + name + "' поза власним діапазоном.");
        }
        return new ConfigKey<>(block, name, def,
            raw -> ((Number) raw).doubleValue(),
            value -> Math.max(min, Math.min(max, value)),
            OnInvalid.CLAMP, min + "…" + max);
    }

    public static ConfigKey<Boolean> bool(String block, String name, boolean def) {
        return new ConfigKey<>(block, name, def,
            raw -> raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw)),
            value -> value,
            OnInvalid.FALLBACK, "true/false");
    }

    /**
     * Рядок із закритим переліком допустимих значень. Саме це не дає
     * записати в конфіг режим, якого не існує.
     */
    public static ConfigKey<String> option(String block, String name, String def, List<String> allowed) {
        if (!allowed.contains(def)) {
            throw new IllegalArgumentException("Дефолт '" + def + "' відсутній у переліку ключа " + name);
        }
        return new ConfigKey<>(block, name, def,
            raw -> String.valueOf(raw).trim(),
            value -> allowed.contains(value) ? value : def,
            OnInvalid.FALLBACK, String.join(" | ", allowed));
    }

    /**
     * Вільний рядок, який звіряється з реєстром у рантаймі (id маньяка
     * тощо). Перелік тут не фіксується навмисно: реєстр наповнюється
     * кодом, а не конфігом, і дублювати його списком було б тим самим
     * мертвим налаштуванням.
     */
    public static ConfigKey<String> freeText(String block, String name, String def, String hint) {
        return new ConfigKey<>(block, name, def,
            raw -> String.valueOf(raw).trim(),
            value -> value.isEmpty() ? def : value,
            OnInvalid.FALLBACK, hint);
    }

    private static void requireWithin(String name, int def, int min, int max) {
        if (def < min || def > max) {
            throw new IllegalArgumentException("Дефолт '" + name + "' поза власним діапазоном.");
        }
    }
}
