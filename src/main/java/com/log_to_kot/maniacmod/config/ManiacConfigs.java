package com.log_to_kot.maniacmod.config;

import com.log_to_kot.maniacmod.ManiacMod;
import dev.shaurmalib.common.config.CachedYamlLoader;
import dev.shaurmalib.common.config.ConfigReloadBus;
import dev.shaurmalib.common.config.ShaurmaConfigTree;
import dev.shaurmalib.forge.config.ConfigModule;
import dev.shaurmalib.libs.snakeyaml.DumperOptions;
import dev.shaurmalib.libs.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Конфіг мода: завантаження, лікування, валідація, гаряче
 * перезавантаження.
 *
 * ── Чому немає жодного кешу на стороні модулів ───────────────────────
 * Значення читаються з незмінного {@link Snapshot}, який цілком
 * замінюється при перезавантаженні. Модуль, що пише
 * {@code ManiacConfigs.get(KEY)}, завжди бачить актуальне число —
 * навіть якщо файл змінили секунду тому.
 *
 * Це відповідь на класичну проблему: частина налаштувань підхоплюється
 * одразу, а частина «тільки після рестарту», бо хтось скопіював
 * значення в поле при старті. Тут копіювати нема куди: гетер дешевий
 * (читання з мапи в пам'яті), тож його кличуть просто щоразу.
 *
 * Модулям, які все ж мусять перебудувати щось похідне (готові об'єкти,
 * зареєстровані слухачі), достатньо підписатись на
 * {@link ConfigReloadBus} — він спрацьовує ПІСЛЯ підміни снапшоту.
 *
 * ── Лікування ────────────────────────────────────────────────────────
 * Тільки поблочне. Відсутній блок дописується в кінець файлу з
 * дефолту в jar, БЕЗ перезапису решти файлу — коментарі й порядок
 * адмінських правок зберігаються. Вміст наявного блока не чіпається
 * ніколи: видалена точка спавну не має вертатись щостарту.
 */
public final class ManiacConfigs {

    /** Незмінний зріз конфігу. Читається, не змінюється. */
    public static final class Snapshot {
        private final Map<String, Object> values;      // path → типізоване значення
        private final Map<String, Object> dataBlocks;  // id блока → сирий вміст

        private Snapshot(Map<String, Object> values, Map<String, Object> dataBlocks) {
            this.values = Map.copyOf(values);
            this.dataBlocks = Map.copyOf(dataBlocks);
        }

        static Snapshot defaults() {
            Map<String, Object> values = new HashMap<>();
            for (ConfigBlock block : ConfigSchema.BLOCKS) {
                for (ConfigKey<?> key : block.keys()) {
                    values.put(key.path(), key.defaultValue());
                }
            }
            return new Snapshot(values, Map.of());
        }
    }

    private static final Yaml YAML = buildYaml();
    private static final CachedYamlLoader LOADER = new CachedYamlLoader();

    private static ShaurmaConfigTree tree;
    private static ConfigReloadBus reloadBus;

    /** Завжди валідний: до init() це чисті дефолти, не null. */
    private static volatile Snapshot snapshot = Snapshot.defaults();

    /** mtime кожного окремого конфігу — база для автопідхоплення. */
    private static final Map<String, Long> lastSeenMtimes = new HashMap<>();

    private ManiacConfigs() {}

    // ── Ініціалізація ────────────────────────────────────────────────────

    /**
     * Викликається один раз із ManiacMod після {@code ShaurmaLib.build()}.
     * Одразу виконує перше завантаження, тому будь-який код після цього
     * рядка вже бачить справжні значення.
     */
    public static void init(ConfigModule module) {
        tree = module.configTree();
        reloadBus = module.reloadBus();
        reload("старт сервера");
    }

    public static ConfigReloadBus reloadBus() {
        if (reloadBus == null) throw new IllegalStateException(
            "ConfigReloadBus недоступний до ManiacConfigs.init(...).");
        return reloadBus;
    }

    public static Path namespaceDirectory() {
        if (tree == null) throw new IllegalStateException("Конфіг ще не ініціалізований.");
        return tree.namespaceDir();
    }

    // ── Читання ──────────────────────────────────────────────────────────

    /**
     * Значення ключа. Завжди валідне й завжди актуальне — прочитати
     * неоголошений ключ неможливо, бо ключ є об'єктом зі схеми.
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(ConfigKey<T> key) {
        Object value = snapshot.values.get(key.path());
        return value == null ? key.defaultValue() : (T) value;
    }

    /**
     * Сирий вміст DATA-блока (розмітка точок, зони). Порожня мапа,
     * якщо блока немає — викликач сам вирішує, що це означає.
     */
    public static Map<String, Object> dataBlock(ConfigBlock block) {
        Object raw = snapshot.dataBlocks.get(block.id());
        return raw instanceof Map<?, ?> map ? castMap(map) : Map.of();
    }

    // ── Перезавантаження ─────────────────────────────────────────────────

    /**
     * Перечитує файл, лікує відсутні блоки, валідує, підміняє снапшот
     * і сповіщає підписників.
     *
     * Порядок навмисний: снапшот підмінюється ДО {@code fireReload},
     * тому підписник у своєму обробнику вже читає нові значення.
     */
    public static ConfigDiagnostics reload(String reason) {
        ConfigDiagnostics diag = new ConfigDiagnostics();
        if (tree == null) return diag;

        Map<String, Map<String, Object>> roots = new LinkedHashMap<>();
        for (ConfigBlock block : ConfigSchema.BLOCKS) {
            Path file = configFile(block);
            Map<String, Object> defaults = readJarDefaults(block);
            heal(block, file, defaults, diag);
            roots.put(block.id(), readSection(file, block.id()));
            lastSeenMtimes.put(block.fileName(), mtimeOf(file));
        }

        Snapshot next = resolve(roots, diag);

        snapshot = next;

        report(reason, diag);

        if (reloadBus != null) {
            reloadBus.fireReload(ManiacMod.MOD_ID);
        }
        return diag;
    }

    /**
     * Автопідхоплення зміни файлу. Викликається раз на секунду з
     * серверного тіку — якщо адмін відредагував yml у редакторі,
     * зміни діють одразу, без команди й без рестарту.
     */
    public static void tickWatcher() {
        if (tree == null) return;
        for (ConfigBlock block : ConfigSchema.BLOCKS) {
            long mtime = mtimeOf(configFile(block));
            if (mtime != lastSeenMtimes.getOrDefault(block.fileName(), -1L)) {
                reload("файл " + block.fileName() + " змінено на диску");
                return;
            }
        }
    }

    // ── Лікування ────────────────────────────────────────────────────────

    /**
     * Лікування файлу, на двох рівнях.
     *
     * ── Рівень 1: файлу немає взагалі ────────────────────────────────
     * Створюється повністю з дефолту в jar. Якщо самого блока в jar
     * немає (динамічний блок архетипу: {@code maniac_stats/<id>.yml} —
     * його фізично не може бути в ресурсах, бо маньяки описані кодом),
     * дефолти беруться зі схеми — інакше адмін отримав би порожній файл.
     *
     * ── Рівень 2: файл є, але в ньому бракує ключів схеми ────────────
     * Тільки для {@link ConfigBlock.Kind#SETTINGS}. Це той випадок, коли
     * мод оновився й отримав нове налаштування: без дописування адмін
     * ніколи б не побачив його у файлі й не знав би, що його можна
     * змінити (значення жило б лише в пам'яті як дефолт). Відсутні ключі
     * ДОПИСУЮТЬСЯ В КІНЕЦЬ файлу зі значенням із jar-дефолту.
     *
     * Що НЕ чіпається ніколи: значення, які адмін уже має; його
     * коментарі; порядок; ключі, яких нема в схемі. Тобто адмін, який
     * поправив число, не побачить, що воно повернулось, — дописується
     * лише те, чого у файлі немає взагалі.
     *
     * ── Чому це не суперечить "не відновлювати вміст блока" ──────────
     * Той принцип стосується {@code DATA}-блоків (списки точок, зон):
     * там склад ключів вільний, і видалений адміном елемент не має
     * повертатись щостарту. У {@code SETTINGS} склад ключів ФІКСОВАНИЙ
     * схемою, тож "адмін навмисно видалив ключ" не має сенсу — видалити
     * скалярне налаштування означало б лише повернути йому дефолт, що
     * дописування й робить, тільки тепер явно й видимо.
     *
     * Працює з ТЕКСТОМ файлу, а не з перезаписом розпарсеного дерева:
     * інакше з файлу зникли б коментарі адміна й порядок його правок.
     */
    private static void heal(ConfigBlock block, Path file, Map<String, Object> defaults,
                             ConfigDiagnostics diag) {
        if (!Files.exists(file)) {
            // Файла немає → пишемо його з дефолтів у jar. Для блока, якого
            // в jar немає взагалі (динамічний блок архетипу:
            // maniac_stats/<id>.yml), джерелом дефолтів лишається схема —
            // інакше адмін отримав би ПОРОЖНІЙ файл, а значення жили б
            // тільки в пам'яті (і жодного рядка, щоб їх побачити й
            // поправити). Ключі динамічного блока все одно зі схеми.
            Map<String, Object> content = defaults.isEmpty() ? schemaDefaults(block) : defaults;
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, YAML.dump(content), StandardCharsets.UTF_8);
                LOADER.invalidate(file);
                diag.healedBlock(file.getFileName().toString());
            } catch (IOException e) {
                ManiacMod.LOGGER.warn("[config] не вдалось створити {}: {}", file.getFileName(), e.getMessage());
            }
            return;
        }
        if (block.kind() != ConfigBlock.Kind.SETTINGS) return;
        healMissingKeys(block, file, defaults, diag);
    }

    /**
     * Блок у вигляді ПЛОСКОЇ мапи {@code ім'я ключа → дефолт схеми}. Той
     * самий формат, що адмін бачить у плоских файлах ({@code generators.yml}),
     * і той, на який розраховані {@link #healMissingKeys} та
     * {@link #readSection} (блок без вкладеної обгортки читається з
     * кореня файлу).
     */
    private static Map<String, Object> schemaDefaults(ConfigBlock block) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (ConfigKey<?> key : block.keys()) map.put(key.name(), key.defaultValue());
        return map;
    }

    /**
     * Дописує в кінець наявного файлу ключі схеми, яких у ньому немає.
     *
     * Розташування ключа у файлі залежить від формату, який уже
     * використовує адмін (ці два формати співіснують у проєкті):
     *   • ПЛОСКИЙ ({@code generators.yml}: ключі в корені) — новий
     *     рядок {@code name: value} у кінець файлу;
     *   • ВКЛАДЕНИЙ ({@code maniacs.yml}: {@code maniac:} а під ним
     *     відступ і ключі) — рядок з відступом дописується в кінець
     *     саме цього блока, а не файлу, інакше він потрапив би не в
     *     той розділ.
     * Формат визначається за розпарсеним файлом: якщо в корені є мапа
     * під ключем {@code block.id()} — вкладений, інакше плоский.
     *
     * Вкладений блок доповнюється ВСЕРЕДИНІ себе — див.
     * {@link #insertIntoNestedBlock}; файл із кількома блоками (як
     * {@code maniacs.yml}) від цього не страждає. Якщо блок записаний
     * у формі, яку текстом безпечно не доповнити, ключі не дописуються
     * (лише WARN у лог, значення діють з дефолту в пам'яті).
     */
    private static void healMissingKeys(ConfigBlock block, Path file,
                                        Map<String, Object> defaults, ConfigDiagnostics diag) {
        Map<String, Object> root = readDisk(file);
        Object nested = root.get(block.id());
        boolean isNested = nested instanceof Map<?, ?>;
        Map<String, Object> present = isNested ? castMap((Map<?, ?>) nested) : root;

        Map<String, Object> defaultSection = defaults.get(block.id()) instanceof Map<?, ?> dm
            ? castMap(dm) : defaults;

        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.List<String> added = new java.util.ArrayList<>();
        String indent = isNested ? "  " : "";
        for (ConfigKey<?> key : block.keys()) {
            if (present.containsKey(key.name())) continue;
            // Значення з jar-дефолту, а якщо там нема — з дефолту схеми:
            // так ключ, якого ще не встигли додати в yml у ресурсах,
            // усе одно потрапить у файл.
            Object value = defaultSection.containsKey(key.name())
                ? defaultSection.get(key.name()) : key.defaultValue();
            lines.add(indent + key.name() + ": " + scalarToYaml(value));
            added.add(key.name());
        }
        if (lines.isEmpty()) return;

        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String eol = text.contains("\r\n") ? "\r\n" : "\n";

            String updated;
            if (isNested) {
                updated = insertIntoNestedBlock(text, block.id(), lines, eol);
                if (updated == null) {
                    diag.keysNotWritten(block.id(), added);
                    return;
                }
            } else {
                StringBuilder out = new StringBuilder(text);
                if (!text.isEmpty() && !text.endsWith("\n")) out.append(eol);
                for (String line : lines) out.append(line).append(eol);
                updated = out.toString();
            }

            Files.writeString(file, updated, StandardCharsets.UTF_8);
            LOADER.invalidate(file);
            diag.healedKeys(file.getFileName().toString(), added);
        } catch (IOException e) {
            ManiacMod.LOGGER.warn("[config] не вдалось доповнити {}: {}", file.getFileName(), e.getMessage());
        }
    }

    /**
     * Вставляє рядки в кінець вкладеного блока {@code id:} і повертає
     * новий текст файлу; {@code null}, якщо безпечно це зробити не
     * вдалось.
     *
     * Кілька блоків живуть в одному файлі ({@code maniacs.yml}:
     * {@code maniac:} і {@code maniac_selection:}; {@code points.yml}:
     * {@code map:}, {@code loot:} і DATA-ключі), тож дописати в кінець
     * ФАЙЛУ означало б покласти ключ під чужий блок. Замість цього
     * знаходимо рядок {@code id:} у корені, читаємо тіло вниз до
     * наступного кореневого ключа (рядка без відступу, що не коментар),
     * і вставляємо після ОСТАННЬОГО значущого (не порожнього і не
     * коментаря) рядка тіла — так нові ключі стоять разом зі своїми
     * сусідами, а порожні рядки й коментарі між блоками лишаються на
     * місці.
     *
     * {@code null}: рядок {@code id:} не знайдено у вигляді «id:» без
     * значення в тому ж рядку (наприклад, адмін записав блок у
     * flow-стилі {@code id: {a: 1}}) — такий формат текстом не
     * доповнити, тому чесно відмовляємось і лишаємо файл без змін.
     */
    private static String insertIntoNestedBlock(String text, String id, java.util.List<String> lines,
                                                String eol) {
        String[] rows = text.split("\r?\n", -1);
        int header = -1;
        for (int i = 0; i < rows.length; i++) {
            String r = rows[i];
            if (r.isEmpty()) continue;
            char c = r.charAt(0);
            if (c == ' ' || c == '\t' || c == '#') continue;
            if (r.stripTrailing().equals(id + ":")) { header = i; break; }
        }
        if (header < 0) return null;

        // Кінець тіла: останній значущий рядок ДО наступного кореневого ключа.
        int lastBody = header;
        for (int i = header + 1; i < rows.length; i++) {
            String r = rows[i];
            if (r.isBlank()) continue;
            char c = r.charAt(0);
            if (c == '#') continue;                       // коментар не завершує тіло й не є його частиною
            if (c != ' ' && c != '\t') break;             // наступний кореневий ключ
            lastBody = i;
        }

        java.util.List<String> result = new java.util.ArrayList<>(java.util.Arrays.asList(rows));
        result.addAll(lastBody + 1, lines);
        return String.join(eol, result);
    }

    /**
     * Скаляр (число/bool/рядок) у вигляді, безпечному для ОДНОГО рядка
     * YAML.
     *
     * ── Чому не {@code YAML.dump(value)} для рядків ──────────────────
     * Дампер серіалізує голий скаляр верхнього рівня як окремий документ
     * і може дописати маркер кінця документа ({@code RANDOM\n...}); після
     * {@code trim()} у файл потрапило б {@code playerMode: RANDOM\n...} —
     * зламаний YAML. Тому рядок беремо в одинарні лапки самі (єдине
     * екранування, яке потрібне: {@code '} → {@code ''}). Лапки завжди,
     * навіть де вони не обов'язкові, — так порожній рядок лишається
     * {@code ''} (а не {@code null}), а значення на кшталт {@code yes},
     * {@code 123} чи {@code a: b} не перетворюються на інший тип.
     */
    private static String scalarToYaml(Object value) {
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value == null) return "''";
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    // ── Розбір ───────────────────────────────────────────────────────────

    private static Snapshot resolve(Map<String, Map<String, Object>> roots,
                                    ConfigDiagnostics diag) {
        Map<String, Object> values = new HashMap<>();
        Map<String, Object> dataBlocks = new LinkedHashMap<>();

        for (ConfigBlock block : ConfigSchema.BLOCKS) {
            Map<String, Object> section = roots.getOrDefault(block.id(), Collections.emptyMap());

            if (block.kind() == ConfigBlock.Kind.DATA) {
                dataBlocks.put(block.id(), section);
                continue;
            }

            for (ConfigKey<?> key : block.keys()) {
                values.put(key.path(), key.resolve(section.get(key.name()), diag));
            }

            // Мертві налаштування: ключ у файлі, якого немає в схемі.
            for (String present : section.keySet()) {
                boolean declared = block.keys().stream()
                    .anyMatch(k -> k.name().equals(present));
                if (!declared) diag.unknownKey(block.id(), present);
            }
        }

        return new Snapshot(values, dataBlocks);
    }

    // ── Запис (з GUI-меню налаштувань) ──────────────────────────────────

    /**
     * Пише ОДНЕ значення в YAML-файл на диску й одразу перезавантажує
     * снапшот. Викликається лише з обробника {@code SettingsChangePacket}
     * (сервер, права оператора вже перевірені раніше в ланцюжку).
     *
     * ── Чому текстом мапи, а не point-fix у файлі ────────────────────
     * На відміну від {@link #heal}, який лише ДОПИСУЄ відсутній блок і
     * ніколи не чіпає наявний вміст (щоб не стерти коментарі й порядок
     * адмінських правок), запис із GUI — це навпаки, свідома зміна
     * ІСНУЮЧОГО значення на бажання адміна. Тут файл перечитується як
     * мапа, один ключ у ній підмінюється, і мапа переписується цілком
     * через {@link #YAML}. Коментарі в файлі (якщо адмін їх туди
     * дописав руками) цим перезаписом губляться — це усвідомлений
     * компроміс: GUI не вміє редагувати текст, лише значення, тому не
     * може їх зберегти. Хто хоче зберегти власні коментарі — редагує
     * yml руками і чекає на автопідхоплення ({@link #tickWatcher()}),
     * а не тисне кнопки в меню.
     *
     * @return null при успіху; людський опис помилки, якщо ключ
     *         невідомий або значення не проходить валідацію ключа.
     */
    public static synchronized String set(String blockId, String keyName, String rawValue) {
        if (tree == null) return "Конфіг ще не ініціалізований.";
        ConfigBlock block = ConfigSchema.blockById(blockId);
        if (block == null || block.kind() != ConfigBlock.Kind.SETTINGS) {
            return "Невідомий блок налаштувань: " + blockId;
        }
        ConfigKey<?> key = block.keys().stream()
            .filter(k -> k.name().equals(keyName))
            .findFirst().orElse(null);
        if (key == null) {
            return "Невідомий ключ " + keyName + " у блоці " + blockId;
        }

        Object parsedRaw;
        try {
            parsedRaw = parseForKey(key, rawValue);
        } catch (RuntimeException e) {
            return "Значення '" + rawValue + "' не підходить для " + key.path()
                + " (очікується " + key.describeRange() + ")";
        }

        // resolve() і лікує (clamp/fallback), і валідує одночасно — та
        // сама логіка, що при читанні файлу, тому GUI не може записати
        // те, чого reload() потім сам би виправив мовчки.
        ConfigDiagnostics probe = new ConfigDiagnostics();
        Object corrected = key.resolve(parsedRaw, probe);

        Path file = tree.namespaceDir().resolve(block.fileName());
        // Копія, а не пряма мутація: readDisk() може повернути мапу з
        // внутрішнього кешу CachedYamlLoader (LOADER.readYamlCached) —
        // писати в неї напряму означає псувати чужий кеш-запис до того,
        // як invalidate() встигне його прибрати.
        Map<String, Object> section = new LinkedHashMap<>(readDisk(file));
        Object legacy = section.get(block.id());
        Map<String, Object> target = legacy instanceof Map<?, ?> m
            ? new LinkedHashMap<>(castMap(m))
            : section;
        target.put(key.name(), corrected);
        if (legacy instanceof Map<?, ?>) section.put(block.id(), target);

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, YAML.dump(section), StandardCharsets.UTF_8);
            LOADER.invalidate(file);
        } catch (IOException e) {
            return "Не вдалось записати " + block.fileName() + ": " + e.getMessage();
        }

        reload("GUI-меню налаштувань: " + key.path());
        return null;
    }

    /**
     * Сире текстове поле з GUI → тип, який очікує {@link ConfigKey#resolve}
     * (Number для integer/decimal, Boolean для bool, String інакше).
     * Сам {@code resolve} потім ще й клемпить/валідує — тут лише
     * переклад рядка в правильний Java-тип без домішки range-логіки.
     */
    private static Object parseForKey(ConfigKey<?> key, String rawValue) {
        Object def = key.defaultValue();
        if (def instanceof Integer) return Integer.parseInt(rawValue.trim());
        if (def instanceof Double) return Double.parseDouble(rawValue.trim());
        if (def instanceof Boolean) return Boolean.parseBoolean(rawValue.trim());
        return rawValue.trim();
    }

    // ── Введення-виведення ───────────────────────────────────────────────

    private static Path configFile() {
        return tree.namespaceDir().resolve(ConfigSchema.FILE_NAME);
    }

    private static Path configFile(ConfigBlock block) {
        return tree.namespaceDir().resolve(block.fileName());
    }

    private static Map<String, Object> readDisk(Path file) {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            Map<String, Object> data = LOADER.readYamlCached(file, YAML);
            return data != null ? data : new LinkedHashMap<>();
        } catch (IOException | RuntimeException e) {
            // Зламаний YAML не має валити сервер: працюємо на дефолтах
            // і голосно кажемо про це в лог.
            ManiacMod.LOGGER.error("[config] {} не читається ({}), працюю на дефолтах",
                ConfigSchema.FILE_NAME, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static Map<String, Object> readSection(Path file, String blockId) {
        Map<String, Object> root = readDisk(file);
        Object legacy = root.get(blockId);
        return legacy instanceof Map<?, ?> map ? castMap(map) : root;
    }

    private static Map<String, Object> readJarDefaults() {
        try (InputStream in = ManiacMod.class.getResourceAsStream(ConfigSchema.DEFAULT_RESOURCE)) {
            if (in == null) return Map.of();
            Object root = YAML.load(in);
            return root instanceof Map<?, ?> map ? castMap(map) : Map.of();
        } catch (IOException | RuntimeException e) {
            ManiacMod.LOGGER.warn("[config] дефолт у jar недоступний: {}", e.getMessage());
            return Map.of();
        }
    }

    private static Map<String, Object> readJarDefaults(ConfigBlock block) {
        String resource = "/config/maniacmod/" + block.fileName();
        try (InputStream in = ManiacMod.class.getResourceAsStream(resource)) {
            if (in == null) return Map.of();
            Object root = YAML.load(in);
            return root instanceof Map<?, ?> map ? castMap(map) : Map.of();
        } catch (IOException | RuntimeException e) {
            ManiacMod.LOGGER.warn("[config] дефолт {} недоступний: {}", block.fileName(), e.getMessage());
            return Map.of();
        }
    }

    private static long mtimeOf(Path file) {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private static void report(String reason, ConfigDiagnostics diag) {
        if (diag.isEmpty()) {
            ManiacMod.LOGGER.info("[config] перезавантажено ({}), зауважень немає", reason);
            return;
        }
        ManiacMod.LOGGER.info("[config] перезавантажено ({}), зауважень: {}",
            reason, diag.entries().size());
        for (ConfigDiagnostics.Entry entry : diag.entries()) {
            if (entry.level() == ConfigDiagnostics.Level.WARN) {
                ManiacMod.LOGGER.warn("[config] {}", entry.message());
            } else {
                ManiacMod.LOGGER.info("[config] {}", entry.message());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Yaml buildYaml() {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);
        return new Yaml(opts);
    }
}
