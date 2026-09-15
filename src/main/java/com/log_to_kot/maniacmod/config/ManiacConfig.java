package com.log_to_kot.maniacmod.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * ══════════════════════════════════════════════════════════════
 *  КОНФІГУРАЦІЯ MANIAC MOD
 * ══════════════════════════════════════════════════════════════
 *
 *  Файл: config/maniacmod-server.toml
 *  (створюється автоматично при першому запуску)
 *
 *  [generators]
 *      repairsRequired      = 5      # скільки разів лагодити 1 генератор
 *      repairFailChance     = 0.15   # шанс зриву (0.0–1.0)
 *      repairTicksPerStage  = 200    # тіків на 1 етап (200 = 10 сек)
 *
 *  [game]
 *      gameDurationSeconds  = 900    # тривалість гри
 *      reviveWindowSeconds  = 180    # вікно воскресіння
 */
public class ManiacConfig {

    public static final ForgeConfigSpec SERVER_SPEC;
    public static final Server SERVER;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        SERVER = new Server(builder);
        SERVER_SPEC = builder.build();
    }

    /**
     * Режим вибору маньяка.
     * RANDOM — випадковий гравець (за замовчуванням)
     * MANUAL — адміністратор вказує вручну командою /maniac setmaniac <гравець>
     */
    public enum ManiacPlayerMode { RANDOM, MANUAL }

    /**
     * Режим вибору типу маньяка.
     * RANDOM — випадковий тип (за замовчуванням)
     * MENU   — гравець-маньяк вибирає з меню перед початком гри
     * FIXED  — завжди один фіксований тип (вказується у fixedManiacType)
     */
    public enum ManiacTypeMode { RANDOM, MENU, FIXED }

    public static class Server {

        public final ForgeConfigSpec.IntValue    generatorRepairsRequired;
        public final ForgeConfigSpec.DoubleValue generatorRepairFailChance;
        public final ForgeConfigSpec.IntValue    generatorRepairTicksPerStage;
        public final ForgeConfigSpec.IntValue    gameDurationSeconds;
        public final ForgeConfigSpec.IntValue    reviveWindowSeconds;

        // ── Вибір маньяка ────────────────────────────────────────────────────
        /**
         * Хто стає маньяком:
         *   RANDOM — випадковий гравець кожного разу
         *   MANUAL — адмін призначає через /maniac setmaniac <гравець>
         */
        public final ForgeConfigSpec.EnumValue<ManiacPlayerMode> maniacPlayerMode;

        /**
         * Який тип маньяка:
         *   RANDOM — випадковий тип (CHUCKY або SLENDERMAN)
         *   MENU   — гравець-маньяк отримує меню і вибирає персонажа сам
         */
        public final ForgeConfigSpec.EnumValue<ManiacTypeMode> maniacTypeMode;

        /**
         * Фіксований тип маньяка (тільки якщо maniacTypeMode = FIXED).
         * Можливі значення: CHUCKY, SLENDERMAN
         */
        public final ForgeConfigSpec.ConfigValue<String> fixedManiacType;

        /**
         * Вмикає кінематографічну сценку на початку гри (відео + title-карта).
         * false → гра починається одразу без сценки.
         */
        public final ForgeConfigSpec.BooleanValue enableIntroScene;

        Server(ForgeConfigSpec.Builder builder) {

            builder.comment("Налаштування генераторів").push("generators");

            generatorRepairsRequired = builder
                .comment("Скільки разів треба полагодити один генератор. [1–10] default: 5")
                .defineInRange("repairsRequired", 5, 1, 10);

            generatorRepairFailChance = builder
                .comment(
                    "Шанс що ремонт зірветься і прогрес скинеться.",
                    "0.0 = ніколи  |  0.15 = 15%  |  1.0 = завжди",
                    "Default: 0.15"
                )
                .defineInRange("repairFailChance", 0.15, 0.0, 1.0);

            generatorRepairTicksPerStage = builder
                .comment("Тіків для одного етапу ремонту (20 тіків = 1 секунда). Default: 200 = 10 сек")
                .defineInRange("repairTicksPerStage", 200, 40, 1200);

            builder.pop().comment("Загальні налаштування гри").push("game");

            gameDurationSeconds = builder
                .comment("Тривалість гри в секундах. Default: 900 (15 хвилин)")
                .defineInRange("gameDurationSeconds", 900, 120, 3600);

            reviveWindowSeconds = builder
                .comment("Скільки секунд можна воскресити гравця після смерті. Default: 180 (3 хвилини)")
                .defineInRange("reviveWindowSeconds", 180, 30, 600);

            builder.pop().comment(
                "═══════════════════════════════════════",
                "  ВИБІР МАНЬЯКА",
                "═══════════════════════════════════════",
                "",
                "  maniacPlayerMode:",
                "    RANDOM — випадковий гравець кожної гри",
                "    MANUAL — адміністратор призначає через /maniac setmaniac <гравець>",
                "",
                "  maniacTypeMode:",
                "    RANDOM — випадковий тип маньяка (CHUCKY або SLENDERMAN)",
                "    FIXED  — завжди один тип, вказаний у fixedManiacType",
                "",
                "  fixedManiacType (тільки коли maniacTypeMode = FIXED):",
                "    CHUCKY     — Чакі (маленький, 6 сек кд)",
                "    SLENDERMAN — Слендермен (великий, 10 сек кд)"
            ).push("maniac_selection");

            maniacPlayerMode = builder
                .comment("Хто стає маньяком: RANDOM або MANUAL")
                .defineEnum("maniacPlayerMode", ManiacPlayerMode.RANDOM);

            maniacTypeMode = builder
                .comment(
                    "Який тип маньяка:",
                    "  RANDOM — випадковий тип кожної гри",
                    "  MENU   — гравець-маньяк бачить меню і вибирає персонажа сам",
                    "  FIXED  — завжди один тип, вказаний у fixedManiacType"
                )
                .defineEnum("maniacTypeMode", ManiacTypeMode.RANDOM);

            fixedManiacType = builder
                .comment(
                    "Тип маньяка коли maniacTypeMode = FIXED.",
                    "Можливі значення: CHUCKY, SLENDERMAN"
                )
                .define("fixedManiacType", "CHUCKY");

            enableIntroScene = builder
                .comment(
                    "Вмикає кінематографічну сценку на початку гри.",
                    "true  — відео + title-карта + розкриття маньяка (default)",
                    "false — гра починається одразу без будь-якої сценки"
                )
                .define("enableIntroScene", true);

            builder.pop();
        }
    }

    public static int    getRepairsRequired()     { return SERVER.generatorRepairsRequired.get(); }
    public static double getRepairFailChance()    { return SERVER.generatorRepairFailChance.get(); }
    public static int    getRepairTicksPerStage() { return SERVER.generatorRepairTicksPerStage.get(); }
    public static int    getGameDuration()        { return SERVER.gameDurationSeconds.get(); }
    public static int    getReviveWindow()        { return SERVER.reviveWindowSeconds.get(); }

    public static ManiacPlayerMode getManiacPlayerMode() { return SERVER.maniacPlayerMode.get(); }
    public static ManiacTypeMode   getManiacTypeMode()   { return SERVER.maniacTypeMode.get(); }
    public static boolean          isIntroSceneEnabled() { return SERVER.enableIntroScene.get(); }

    public static com.log_to_kot.maniacmod.entity.ManiacType getFixedManiacType() {
        String val = SERVER.fixedManiacType.get().toUpperCase().trim();
        try {
            return com.log_to_kot.maniacmod.entity.ManiacType.valueOf(val);
        } catch (IllegalArgumentException e) {
            return com.log_to_kot.maniacmod.entity.ManiacType.CHUCKY; // fallback
        }
    }
}
