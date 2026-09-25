package com.log_to_kot.maniacmod.config;

import java.util.ArrayList;
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
 *
 * ── Чому набір ключів мутабельний ────────────────────────────────────
 * У більшості блоків ключі відомі на етапі компіляції — вони приходять
 * сюди з {@code ConfigSchema}. Але блок параметрів маньяка
 * ({@code maniac_stats_<id>}) фізично не може бути статичним: скільки
 * маньяків, скільки й блоків, і склад ключів кожного визначає КЛАС
 * архетипу. Тому ключі тут — {@link ArrayList}, який архетип доповнює
 * один раз при реєстрації ({@link #addKeys}), ще ДО першого
 * завантаження конфігу ({@code ManiacConfigs.init}). Це не «динамічна
 * схема» взагалі — після реєстрації набір заморожений, і далі працює
 * так само, як статичні блоки: валідація, лікування, діагностика.
 *
 * ── Що таке comment ─────────────────────────────────────────────────
 * Людська назва блока одним рядком. Вона ж — підпис таба в меню
 * налаштувань, коли перекладу ({@code maniacmod.settings.block.<id>})
 * немає, а для динамічного блока його не може бути: id утворюється з
 * коду. Тому перше речення коментаря мусить бути придатним як назва.
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
    private final String fileName;
    private final Kind kind;
    private final List<ConfigKey<?>> keys = new ArrayList<>();
    private final String comment;

    private ConfigBlock(String id, String fileName, Kind kind, List<ConfigKey<?>> keys, String comment) {
        this.id = id;
        this.fileName = fileName;
        this.kind = kind;
        this.keys.addAll(keys);
        this.comment = comment;
    }

    public static ConfigBlock settings(String id, String comment, ConfigKey<?>... keys) {
        return settings(id, id + ".yml", comment, keys);
    }

    public static ConfigBlock settings(String id, String fileName, String comment,
                                       ConfigKey<?>... keys) {
        for (ConfigKey<?> key : keys) {
            if (!key.block().equals(id)) {
                throw new IllegalArgumentException(
                    "Ключ " + key.path() + " оголошений у блоці " + id + " — шляхи розійшлися.");
            }
        }
        return new ConfigBlock(id, fileName, Kind.SETTINGS, List.of(keys), comment);
    }

    public static ConfigBlock data(String id, String comment) {
        return new ConfigBlock(id, id + ".yml", Kind.DATA, List.of(), comment);
    }

    /**
     * Дописує ключі до вже створеного блока — викликається РІВНО один раз
     * на блок, при реєстрації архетипу, до {@code ManiacConfigs.init}.
     *
     * Перевіряє, що ключ оголошено саме для цього блока: інакше ключ
     * ліг би на диск в один файл, а читався б з іншого (шлях ключа
     * складається з {@code block} + {@code name}), і адмін правив би
     * число, яке ні на що не впливає. Повторне додавання ключа з тим
     * самим іменем теж падає — це завжди помилка реєстрації, а не
     * сценарій, який варто «тихо проігнорувати».
     */
    public void addKeys(List<ConfigKey<?>> extra) {
        for (ConfigKey<?> key : extra) {
            if (!key.block().equals(id)) {
                throw new IllegalArgumentException(
                    "Ключ " + key.path() + " дописують у блок " + id + " — шляхи розійшлися.");
            }
            if (containsKey(key.name())) {
                throw new IllegalArgumentException("Ключ " + key.path() + " уже є в блоці " + id + ".");
            }
            keys.add(key);
        }
    }

    /** Чи оголошено ключ з таким іменем у блоці. */
    public boolean containsKey(String name) {
        for (ConfigKey<?> key : keys) {
            if (key.name().equals(name)) return true;
        }
        return false;
    }

    public String id()                 { return id; }
    public String fileName()           { return fileName; }
    public Kind kind()                 { return kind; }
    public List<ConfigKey<?>> keys()   { return List.copyOf(keys); }
    public String comment()            { return comment; }
}
