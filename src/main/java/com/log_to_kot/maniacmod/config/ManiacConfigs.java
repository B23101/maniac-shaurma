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

    /** mtime файлу на момент останнього читання — база для автопідхоплення. */
    private static volatile long lastSeenMtime = -1;

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

        Path file = configFile();
        Map<String, Object> jarDefaults = readJarDefaults();

        heal(file, jarDefaults, diag);

        Map<String, Object> root = readDisk(file);
        Snapshot next = resolve(root, diag);

        snapshot = next;
        lastSeenMtime = mtimeOf(file);

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
        long mtime = mtimeOf(configFile());
        if (mtime < 0 || mtime == lastSeenMtime) return;
        reload("файл змінено на диску");
    }

    // ── Лікування ────────────────────────────────────────────────────────

    /**
     * Дописує відсутні блоки з дефолту в jar.
     *
     * Працює з ТЕКСТОМ файлу, а не з перезаписом розпарсеного дерева:
     * інакше з файлу зникли б коментарі адміна й порядок його правок.
     * Новий блок просто додається в кінець.
     */
    private static void heal(Path file, Map<String, Object> jarDefaults, ConfigDiagnostics diag) {
        Map<String, Object> onDisk = readDisk(file);
        StringBuilder appended = new StringBuilder();

        for (ConfigBlock block : ConfigSchema.BLOCKS) {
            if (onDisk.containsKey(block.id())) continue; // блок є — всередину не лізем

            Object defaults = jarDefaults.get(block.id());
            if (defaults == null) {
                diag.blockMissingEverywhere(block.id());
                continue;
            }

            appended.append('\n')
                    .append("# ").append(block.comment()).append('\n')
                    .append(YAML.dump(Map.of(block.id(), defaults)));
            diag.healedBlock(block.id());
        }

        if (appended.length() == 0) return;

        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8);
                Files.writeString(file, existing + appended, StandardCharsets.UTF_8);
            } else {
                Files.writeString(file, appended.toString().stripLeading(), StandardCharsets.UTF_8);
            }
            LOADER.invalidate(file);
        } catch (IOException e) {
            ManiacMod.LOGGER.warn("[config] не вдалось дописати відсутні блоки: {}", e.getMessage());
        }
    }

    // ── Розбір ───────────────────────────────────────────────────────────

    private static Snapshot resolve(Map<String, Object> root, ConfigDiagnostics diag) {
        Map<String, Object> values = new HashMap<>();
        Map<String, Object> dataBlocks = new LinkedHashMap<>();

        for (ConfigBlock block : ConfigSchema.BLOCKS) {
            Object rawBlock = root.get(block.id());
            Map<String, Object> section = rawBlock instanceof Map<?, ?> m
                ? castMap(m) : Collections.emptyMap();

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

        // Блок у файлі, якого немає в схемі.
        for (String present : root.keySet()) {
            if (ConfigSchema.blockById(present) == null) diag.unknownBlock(present);
        }

        return new Snapshot(values, dataBlocks);
    }

    // ── Введення-виведення ───────────────────────────────────────────────

    private static Path configFile() {
        return tree.namespaceDir().resolve(ConfigSchema.FILE_NAME);
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
