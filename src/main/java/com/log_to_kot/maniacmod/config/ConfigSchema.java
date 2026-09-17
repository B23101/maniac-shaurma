package com.log_to_kot.maniacmod.config;

import java.util.List;

/**
 * Уся схема конфігу в одному файлі.
 *
 * ── Два правила, які тут тримаються ──────────────────────────────────
 *
 * 1. **Немає мертвих налаштувань.** Ключ існує лише як константа
 *    нижче, і читається лише через {@code ManiacConfigs.get(KEY)}.
 *    Ключ, який нікому не потрібен, видно як невикористану константу.
 *    Ключ у файлі, якого тут немає, мод повідомляє як такий, що ні на
 *    що не впливає.
 *
 * 2. **Немає неможливих налаштувань.** Діапазон або перелік
 *    оголошується поруч із дефолтом. Те, що фізично не може бути
 *    іншим, у конфіг не виноситься взагалі — див. розділ нижче.
 *
 * ── Що свідомо НЕ є налаштуванням ────────────────────────────────────
 *   • Кількість здібностей маньяка (3) — прив'язана до клавіш 1/2/3.
 *   • Кількість слотів пасток (3) — прив'язана до Z/X/C.
 *   • Кількість слотів інвентаря маньяка (0) — у маньяка немає
 *     інвентаря, саме тому клавіші 1–4 вільні.
 *   Винести їх у yml означало б дати адміну зламати розкладку клавіш
 *   значенням, яке нема куди подіти.
 *
 * ── Що таке DATA-блок ────────────────────────────────────────────────
 * Блоки {@link #SPAWN_POINTS} і {@link #ZONES} наповнюють команди, а не
 * людина з переліком ключів. Вони лікуються тільки цілком: якщо блок
 * є — його вміст недоторканний, скільки б записів адмін не видалив.
 */
public final class ConfigSchema {

    private ConfigSchema() {}

    /** Ім'я файлу в теці namespace. */
    public static final String FILE_NAME = "maniac.yml";

    /** Шлях до дефолту всередині jar (для поблочного лікування). */
    public static final String DEFAULT_RESOURCE = "/config/maniacmod/" + FILE_NAME;

    // ── match ────────────────────────────────────────────────────────────

    public static final ConfigKey<Integer> MIN_PLAYERS =
        ConfigKey.integer("match", "minPlayers", 2, 2, 32);

    public static final ConfigKey<Integer> CINEMATIC_SECONDS =
        ConfigKey.integer("match", "cinematicSeconds", 20, 0, 300);

    public static final ConfigKey<Integer> ROLE_REVEAL_SECONDS =
        ConfigKey.integer("match", "roleRevealSeconds", 5, 1, 60);

    public static final ConfigKey<Integer> ENDING_SECONDS =
        ConfigKey.integer("match", "endingSeconds", 10, 1, 120);

    // ── map ──────────────────────────────────────────────────────────────

    public static final ConfigKey<Integer> MAP_SIZE_BLOCKS =
        ConfigKey.integer("map", "sizeBlocks", 300, 64, 2048);

    public static final ConfigKey<Integer> MIN_SURVIVOR_TO_MANIAC =
        ConfigKey.integer("map", "minSurvivorToManiacBlocks", 60, 0, 1024);

    public static final ConfigKey<Integer> MIN_SURVIVOR_TO_PEER =
        ConfigKey.integer("map", "minSurvivorToPeerBlocks", 20, 0, 1024);

    public static final ConfigKey<Double> ITEM_POINT_ENGAGE_RATIO =
        ConfigKey.decimal("map", "itemPointEngageRatio", 0.6, 0.0, 1.0);

    public static final ConfigKey<Double> RELAXATION_STEP =
        ConfigKey.decimal("map", "relaxationStep", 0.85, 0.1, 0.99);

    public static final ConfigKey<Integer> MAX_RELAXATION_PASSES =
        ConfigKey.integer("map", "maxRelaxationPasses", 6, 0, 32);

    // ── generators ───────────────────────────────────────────────────────

    public static final ConfigKey<Integer> GENERATORS_REQUIRED =
        ConfigKey.integer("generators", "generatorsRequired", 5, 1, 32);

    /**
     * Скільки ЗАЙВИХ (нерозв'язуваних наперед) точок генераторів
     * додається на кожного маньяка в матчі — понад generatorsRequired.
     * Проти кемпінгу: якщо полагодити треба 6, а маньяк один, то
     * спавниться 6 + 1×bonusGeneratorsPerManiac = 7 точок; з двома
     * маньяками — 6 + 2×bonus. Полагодити завжди треба рівно
     * generatorsRequired, решта — відволікаючі.
     */
    public static final ConfigKey<Integer> BONUS_GENERATORS_PER_MANIAC =
        ConfigKey.integer("generators", "bonusGeneratorsPerManiac", 1, 0, 8);

    public static final ConfigKey<Integer> MINIGAMES_PER_GENERATOR =
        ConfigKey.integer("generators", "minigamesPerGenerator", 5, 1, 20);

    public static final ConfigKey<Double> MINIGAME_FAIL_CHANCE =
        ConfigKey.decimal("generators", "minigameFailChance", 0.15, 0.0, 1.0);

    public static final ConfigKey<Integer> MINIGAME_TICKS =
        ConfigKey.integer("generators", "minigameTicks", 200, 20, 1200);

    public static final ConfigKey<Integer> FUEL_REQUIRED_PERCENT =
        ConfigKey.integer("generators", "fuelRequiredPercent", 200, 100, 500);

    public static final ConfigKey<Integer> FUEL_PER_CANISTER =
        ConfigKey.integer("generators", "fuelPerCanister", 100, 10, 500);

    public static final ConfigKey<Integer> HIGHLIGHT_DURATION_TICKS =
        ConfigKey.integer("generators", "highlightDurationTicks", 100, 20, 600);

    public static final ConfigKey<Integer> FAIL_FLASH_TICKS =
        ConfigKey.integer("generators", "failFlashTicks", 60, 0, 600);

    public static final ConfigKey<Integer> MINIGAME_FAIL_LOSS_PERCENT =
        ConfigKey.integer("generators", "minigameFailLossPercent", 0, 0, 100);

    public static final ConfigKey<Integer> REPAIR_SECONDS_PER_STAGE =
        ConfigKey.integer("generators", "repairSecondsPerStage", 10, 1, 600);

    public static final ConfigKey<Double> SCREWDRIVER_SPEED_BONUS =
        ConfigKey.decimal("generators", "screwdriverSpeedBonus", 0.25, 0.0, 5.0);

    public static final ConfigKey<Double> SCREWDRIVER_MINIGAME_REDUCTION =
        ConfigKey.decimal("generators", "screwdriverMinigameReduction", 0.25, 0.0, 1.0);

    public static final ConfigKey<String> GENERATOR_STAGE_ORDER =
        ConfigKey.option("generators", "stageOrder", "REPAIR_THEN_FUEL",
            List.of("REPAIR_THEN_FUEL", "FUEL_THEN_REPAIR"));

    // ── survivors ────────────────────────────────────────────────────────

    public static final ConfigKey<Integer> SURVIVOR_MAX_HP =
        ConfigKey.integer("survivors", "maxHp", 100, 1, 1000);

    public static final ConfigKey<Integer> SURVIVOR_SLOTS =
        ConfigKey.integer("survivors", "inventorySlots", 4, 1, 9);

    public static final ConfigKey<Integer> FLASHLIGHT_COOLDOWN_TICKS =
        ConfigKey.integer("survivors", "flashlightCooldownTicks", 600, 20, 6000);

    public static final ConfigKey<Integer> RESCUE_TICKS =
        ConfigKey.integer("survivors", "rescueTicks", 300, 20, 6000);

    public static final ConfigKey<Double> RESCUE_HELPER_BONUS =
        ConfigKey.decimal("survivors", "rescueHelperBonus", 0.2, 0.0, 1.0);

    public static final ConfigKey<Double> STAMINA_DRAIN_PER_TICK =
        ConfigKey.decimal("survivors", "staminaDrainPerTick", 0.008, 0.0, 1.0);

    public static final ConfigKey<Double> STAMINA_REGEN_PER_TICK =
        ConfigKey.decimal("survivors", "staminaRegenPerTick", 0.004, 0.0, 1.0);

    public static final ConfigKey<Integer> FALL_KNOCKDOWN_HEIGHT =
        ConfigKey.integer("survivors", "fallKnockdownHeightBlocks", 4, 2, 64);

    public static final ConfigKey<Double> LEG_BREAK_CHANCE =
        ConfigKey.decimal("survivors", "legBreakChance", 0.35, 0.0, 1.0);

    public static final ConfigKey<Integer> HEARTBEAT_RANGE_BLOCKS =
        ConfigKey.integer("survivors", "heartbeatRangeBlocks", 12, 0, 128);

    // ── maniac ───────────────────────────────────────────────────────────

    /**
     * Дальність удару маньяка. Саме те, що просили редагувати —
     * різні маньяки можуть перевизначати її у своєму архетипі, а це
     * базове значення.
     */
    public static final ConfigKey<Double> ATTACK_RANGE_BLOCKS =
        ConfigKey.decimal("maniac", "attackRangeBlocks", 3.5, 1.0, 8.0);

    public static final ConfigKey<Integer> ATTACK_COOLDOWN_TICKS =
        ConfigKey.integer("maniac", "attackCooldownTicks", 120, 10, 600);

    public static final ConfigKey<Integer> ATTACK_DAMAGE =
        ConfigKey.integer("maniac", "attackDamage", 50, 1, 1000);

    public static final ConfigKey<Integer> TRAP_PLACE_COOLDOWN_TICKS =
        ConfigKey.integer("maniac", "trapPlaceCooldownTicks", 500, 20, 6000);

    public static final ConfigKey<Integer> TRAP_MIN_DISTANCE_TO_PLAYER =
        ConfigKey.integer("maniac", "trapMinDistanceToPlayerBlocks", 3, 0, 32);

    // ── maniac_selection ─────────────────────────────────────────────────

    public static final ConfigKey<String> MANIAC_PLAYER_MODE =
        ConfigKey.option("maniac_selection", "playerMode", "RANDOM",
            List.of("RANDOM", "MANUAL"));

    public static final ConfigKey<String> MANIAC_TYPE_MODE =
        ConfigKey.option("maniac_selection", "typeMode", "RANDOM",
            List.of("RANDOM", "MENU", "FIXED"));

    /**
     * Id маньяка для режиму FIXED. Перелік не фіксується: маньяків
     * реєструє код, і дублювати той список у конфігу означало б
     * тримати два джерела правди. Невідомий id мод повідомляє при
     * старті матчу й бере випадкового.
     */
    public static final ConfigKey<String> FIXED_MANIAC_ID =
        ConfigKey.freeText("maniac_selection", "fixedManiacId", "",
            "id з ManiacRegistry; порожньо = випадковий");

    public static final ConfigKey<Integer> SELECTION_TIMEOUT_SECONDS =
        ConfigKey.integer("maniac_selection", "selectionTimeoutSeconds", 60, 5, 300);

    public static final ConfigKey<Boolean> INTRO_SCENE_ENABLED =
        ConfigKey.bool("maniac_selection", "enableIntroScene", true);

    // ── loot ─────────────────────────────────────────────────────────────

    public static final ConfigKey<Double> GROUND_ITEM_PICKUP_RANGE =
        ConfigKey.decimal("loot", "pickupRangeBlocks", 2.0, 0.5, 6.0);

    public static final ConfigKey<Integer> GROUND_ITEM_FALL_MAX_TICKS =
        ConfigKey.integer("loot", "fallMaxTicks", 200, 20, 1200);

    // ── Реєстр блоків ────────────────────────────────────────────────────

    public static final ConfigBlock MATCH = ConfigBlock.settings("match", "start_rules.yml",
        "Тривалості технічних фаз і мінімум гравців.",
        MIN_PLAYERS, CINEMATIC_SECONDS, ROLE_REVEAL_SECONDS, ENDING_SECONDS);

    public static final ConfigBlock MAP = ConfigBlock.settings("map", "points.yml",
        "Розмір карти й правила розкидання гравців.",
        MAP_SIZE_BLOCKS, MIN_SURVIVOR_TO_MANIAC, MIN_SURVIVOR_TO_PEER,
        ITEM_POINT_ENGAGE_RATIO, RELAXATION_STEP, MAX_RELAXATION_PASSES);

    public static final ConfigBlock GENERATORS = ConfigBlock.settings("generators", "generators.yml",
        "Ремонт, бензин і підсвітка генераторів.",
        GENERATORS_REQUIRED, BONUS_GENERATORS_PER_MANIAC,
        MINIGAMES_PER_GENERATOR, MINIGAME_FAIL_CHANCE,
        MINIGAME_TICKS, FUEL_REQUIRED_PERCENT, FUEL_PER_CANISTER,
        HIGHLIGHT_DURATION_TICKS, FAIL_FLASH_TICKS, MINIGAME_FAIL_LOSS_PERCENT,
        REPAIR_SECONDS_PER_STAGE, SCREWDRIVER_SPEED_BONUS,
        SCREWDRIVER_MINIGAME_REDUCTION, GENERATOR_STAGE_ORDER);

    public static final ConfigBlock SURVIVORS = ConfigBlock.settings("survivors", "survivors.yml",
        "Здоров'я, стаміна, падіння, підняття непритомних.",
        SURVIVOR_MAX_HP, SURVIVOR_SLOTS, FLASHLIGHT_COOLDOWN_TICKS, RESCUE_TICKS,
        RESCUE_HELPER_BONUS, STAMINA_DRAIN_PER_TICK, STAMINA_REGEN_PER_TICK,
        FALL_KNOCKDOWN_HEIGHT, LEG_BREAK_CHANCE, HEARTBEAT_RANGE_BLOCKS);

    public static final ConfigBlock MANIAC = ConfigBlock.settings("maniac", "maniacs.yml",
        "Базові параметри маньяка. Архетип може перевизначити їх для себе.",
        ATTACK_RANGE_BLOCKS, ATTACK_COOLDOWN_TICKS, ATTACK_DAMAGE,
        TRAP_PLACE_COOLDOWN_TICKS, TRAP_MIN_DISTANCE_TO_PLAYER);

    public static final ConfigBlock MANIAC_SELECTION = ConfigBlock.settings("maniac_selection", "maniacs.yml",
        "Хто стає маньяком і як обирається персонаж.",
        MANIAC_PLAYER_MODE, MANIAC_TYPE_MODE, FIXED_MANIAC_ID,
        SELECTION_TIMEOUT_SECONDS, INTRO_SCENE_ENABLED);

    public static final ConfigBlock LOOT = ConfigBlock.settings("loot", "points.yml",
        "Предмети, що лежать на карті.",
        GROUND_ITEM_PICKUP_RANGE, GROUND_ITEM_FALL_MAX_TICKS);

    public static final ConfigBlock SPAWN_POINTS = ConfigBlock.data("spawn_points",
        "Розмітка точок. Наповнюється командою /maniac point add. "
        + "Вміст ніколи не відновлюється з дефолту — видалена точка лишається видаленою.");

    public static final ConfigBlock ZONES = ConfigBlock.data("zones",
        "Зони карти (втеча, лут). Наповнюється командами. "
        + "Вміст ніколи не відновлюється з дефолту.");

    /** Порядок = порядок блоків у згенерованому файлі. */
    public static final List<ConfigBlock> BLOCKS = List.of(
        MATCH, MAP, GENERATORS, SURVIVORS, MANIAC, MANIAC_SELECTION, LOOT);

    public static ConfigBlock blockById(String id) {
        for (ConfigBlock block : BLOCKS) {
            if (block.id().equals(id)) return block;
        }
        return null;
    }
}
