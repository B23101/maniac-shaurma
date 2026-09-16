package com.log_to_kot.maniacmod.config;

import java.util.List;

/**
 * Блок конфігу — верхній рівень YAML-файлу ({@code generators:},
 * {@code map:} …).
 *
 * ── Чому лікування саме поблочне ─────────────────────────────────────
 * Якщо блока немає взагалі — його беруть із дефолту в jar і дописують
 * у файл. Якщо блок Є, але всередині чогось бракує — НІЧОГО не
 * дописується: відсутній ключ просто читається з дефолту в пам'яті.
 *
 * Причина конкретна. Якщо відновлювати вміст блока, то адмін, який
 * навмисно видалив дві точки спавну зі списку, отримає їх назад при
 * кожному старті — і ніколи не зрозуміє, чому. Наявність блока =
 * «адмін цей розділ бачив і редагував»; відсутність = «розділ новий,
 * мод оновився, покажи його людині».
 */
public final class ConfigBlock {

    public enum Kind {
        /**
         * Налаштування: фіксований набір ключів зі схеми. Значення
         * валідуються, невідомі ключі повідомляються як мертві.
         */
        SETTINGS,
        /**
         * Дані, які наповнює адмін або команди (списки точок, зон).
         * Ключі не валідуються — їх склад заздалегідь невідомий.
         * Лікується так само тільки цілим блоком.
         */
        DATA
    }

    private final String id;
    private final Kind kind;
    private final List<ConfigKey<?>> keys;
    private final String comment;

    private ConfigBlock(String id, Kind kind, List<ConfigKey<?>> keys, String comment) {
        this.id = id;
        this.kind = kind;
        this.keys = List.copyOf(keys);
        this.comment = comment;
    }

    public static ConfigBlock settings(String id, String comment, ConfigKey<?>... keys) {
        for (ConfigKey<?> key : keys) {
            if (!key.block().equals(id)) {
                throw new IllegalArgumentException(
                    "Ключ " + key.path() + " оголошений у блоці " + id + " — шляхи розійшлися.");
            }
        }
        return new ConfigBlock(id, Kind.SETTINGS, List.of(keys), comment);
    }

    public static ConfigBlock data(String id, String comment) {
        return new ConfigBlock(id, Kind.DATA, List.of(), comment);
    }

    public String id()                 { return id; }
    public Kind kind()                 { return kind; }
    public List<ConfigKey<?>> keys()   { return keys; }
    public String comment()            { return comment; }
}
