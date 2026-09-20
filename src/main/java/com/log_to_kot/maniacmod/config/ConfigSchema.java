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

    /** Ім'я ГОЛОВНОГО файлу конфігу в теці namespace. */
    public static final String FILE_NAME = "maniac.yml";

    /** Шлях до дефолту всередині jar (для поблочного лікування). */
    public static final String DEFAULT_RESOURCE = "/config/maniacmod/" + FILE_NAME;

    // ── game (головний блок) ─────────────────────────────────────────

    public static final ConfigKey<Integer> MIN_PLAYERS =
        ConfigKey.integer("game", "minPlayers", 2, 2, 32);

    // Тривалості фази CINEMATIC у конфігу НЕМАЄ навмисно: кінематики ще
    // не існує, тому фаза пропускається ПРОПУСКОМ У КОДІ
    // (MatchOrchestrator: один тік на підготовку плану і одразу SCATTER).
    // Коли кінематика з'явиться — сюди повернеться ключ із тривалістю,
    // а в коді — очікування замість пропуску.

    /**
     * Дебаг-режим для одиночних перевірок: матч можна почати з одним
     * гравцем, і він не завершується, коли з будь-якого боку лишається 0
     * гравців.
     */
    public static final ConfigKey<Boolean> DEBUG_MODE =
        ConfigKey.bool("game", "debugMode", false);

    /**
     * Ким буде єдиний гравець у дебазі.
     *
     * <ul>
     *   <li>{@code AUTO} — звичайний вибір маньяка (реєстр/конфіг);</li>
     *   <li>{@code MANIAC} — ти маньяк, виживих немає взагалі;</li>
     *   <li>{@code SURVIVOR} — ти виживий, маньяка немає взагалі (треба
     *       для перевірки слотів, стаміни, генераторів, луту — усього,
     *       що не залежить від маньяка).</li>
     * </ul>
     */
    public static final ConfigKey<String> DEBUG_ROLE =
        ConfigKey.option("game", "debugRole", "MANIAC", List.of("AUTO", "MANIAC", "SURVIVOR"));

    public static final ConfigKey<Integer> ROLE_REVEAL_SECONDS =
        ConfigKey.integer("game", "roleRevealSeconds", 5, 1, 60);

    public static final ConfigKey<Integer> ENDING_SECONDS =
        ConfigKey.integer("game", "endingSeconds", 10, 1, 120);

    // ── world ─────────────────────────────────────────────────────────────

    /** Час доби у тіках Minecraft, який утримується під час активної гри. */
    public static final ConfigKey<Integer> GAME_TIME_TICKS =
        ConfigKey.integer("world", "gameTimeTicks", 18000, 0, 23999);

    /**
     * Чи утримувати час доби {@link #GAME_TIME_TICKS}.
     *
     * <p>Діє в КОЖНІЙ фазі (і лобі, і гра): час доби — це декорація
     * режиму, а не предмет гри, тому він не має "плисти" між матчами.</p>
     */
    public static final ConfigKey<Boolean> MAINTAIN_GAME_TIME =
        ConfigKey.bool("world", "maintainGameTime", true);

    // ПОГОДИ У ЛОБІ НЕМАЄ ЯК НАЛАШТУВАННЯ: у всіх технічних фазах дощ/гроза
    // прибираються примусово (WorldEnvironmentModule), бо лобі — це
    // очікування, а не гра. Налаштування, яке можна випадково вимкнути й
    // отримати дощ у лобі, тут шкідливе; погодні ключі нижче стосуються
    // ЛИШЕ ігрових фаз.

    public static final ConfigKey<Boolean> WEATHER_EVENTS_ENABLED =
        ConfigKey.bool("world", "weatherEventsEnabled", true);

    /**
     * Шанс запуску події після чергового випадкового інтервалу.
     * WEATHER_TARGET_SHARE додатково не дає погоді перевищити задану
     * частку активної гри.
     */
    public static final ConfigKey<Double> WEATHER_EVENT_CHANCE =
        ConfigKey.decimal("world", "weatherEventChance", 0.20, 0.0, 1.0);

    public static final ConfigKey<Double> WEATHER_TARGET_SHARE =
        ConfigKey.decimal("world", "weatherTargetShare", 0.20, 0.0, 1.0);

    public static final ConfigKey<Integer> WEATHER_MIN_INTERVAL_SECONDS =
        ConfigKey.integer("world", "weatherMinIntervalSeconds", 300, 0, 86400);

    public static final ConfigKey<Integer> WEATHER_MAX_INTERVAL_SECONDS =
        ConfigKey.integer("world", "weatherMaxIntervalSeconds", 900, 0, 86400);

    /** Діапазон тривалості: 120/180/300 секунд дає готові 2/3/5 хвилин. */
    public static final ConfigKey<Integer> WEATHER_MIN_DURATION_SECONDS =
        ConfigKey.integer("world", "weatherMinDurationSeconds", 120, 1, 86400);

    public static final ConfigKey<Integer> WEATHER_MAX_DURATION_SECONDS =
        ConfigKey.integer("world", "weatherMaxDurationSeconds", 300, 1, 86400);

    public static final ConfigKey<Double> WEATHER_THUNDER_CHANCE =
        ConfigKey.decimal("world", "weatherThunderChance", 0.35, 0.0, 1.0);

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
        ConfigKey.integer("generators", "generatorsRequired", 6, 1, 32);

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

    /**
     * Мінімальна відстань (горизонтально, у блоках) між двома генераторами
     * одного матчу. Разом зі {@link #MIN_GENERATOR_TO_MANIAC} це те, що
     * робить випадковий вибір точок «правильно розподіленим»: генератори
     * не збираються купкою й не ламають логіку гри. Якщо розмітка карти
     * не дозволяє дотриматись відстані, вона послаблюється покроково
     * (MAX_RELAXATION_PASSES × RELAXATION_STEP з блока map) — так само,
     * як для розкидання гравців; жорстким лишається лише «одна точка —
     * один генератор».
     */
    public static final ConfigKey<Integer> MIN_GENERATOR_TO_PEER =
        ConfigKey.integer("generators", "minGeneratorToGeneratorBlocks", 40, 0, 1024);

    /** Мінімальна відстань (блоків) від генератора до стартової точки маньяка. */
    public static final ConfigKey<Integer> MIN_GENERATOR_TO_MANIAC =
        ConfigKey.integer("generators", "minGeneratorToManiacBlocks", 40, 0, 1024);

    /**
     * Шанс за один тік утримання ПКМ, що на гравця, який зараз активно
     * лагодить (стадія REPAIR), впаде міні-гра. Перевіряється окремо
     * для КОЖНОГО гравця, що зараз лагодить цей генератор — випадання
     * в одного не чіпає інших: вони продовжують накопичувати прогрес,
     * поки цей один розбирається з міні-грою. Малий дефолт навмисно:
     * при декількох гравцях на одному генераторі сумарний шанс за
     * секунду росте пропорційно їхній кількості.
     */
    public static final ConfigKey<Double> MINIGAME_TRIGGER_CHANCE_PER_TICK =
        ConfigKey.decimal("generators", "minigameTriggerChancePerTick", 0.0015, 0.0, 1.0);

    /**
     * Скільки влучень треба в міні-грі "ціль" (Generator Startup — рухомий
     * повзунок, нерухома ціль): дизайн-скрін показує "Hits: 0/3". Один
     * промах по цілі — миттєвий провал (технічна поломка), спроби НЕ
     * накопичуються.
     */
    public static final ConfigKey<Integer> TARGET_MINIGAME_HITS_REQUIRED =
        ConfigKey.integer("generators", "targetMinigameHitsRequired", 3, 1, 10);

    /**
     * Швидкість руху повзунка міні-гри "ціль", у частках ширини смуги
     * за секунду. Рахується КЛІЄНТОМ локально (детерміновано від
     * seed, який шле сервер при відкритті міні-гри) — сервер лише
     * перевіряє влучення координатою, яку клієнт надсилає на клік,
     * тому саме це значення має бути однаковим для сервера й клієнта
     * (обидва читають той самий ключ конфігу).
     */
    public static final ConfigKey<Double> TARGET_MINIGAME_CURSOR_SPEED =
        ConfigKey.decimal("generators", "targetMinigameCursorSpeed", 0.7, 0.05, 5.0);

    /**
     * Ширина "зони влучання" навколо нерухомої цілі в міні-грі "ціль",
     * у частках ширини смуги (0.0-1.0). Разом із швидкістю повзунка
     * визначає складність — обидва значення підбираються так, щоб
     * міні-гра не була ні тривіальною, ні неможливою.
     */
    public static final ConfigKey<Double> TARGET_MINIGAME_HIT_ZONE_WIDTH =
        ConfigKey.decimal("generators", "targetMinigameHitZoneWidth", 0.08, 0.01, 0.5);

    /**
     * Ліміт часу міні-гри "дроти" (з'єднай 4 кольорові контакти) в
     * тіках — на ВСЮ міні-гру одразу (всі 4 дроти разом), не на кожен
     * дріт окремо. Дизайн: 10 секунд = 200 тіків.
     */
    public static final ConfigKey<Integer> WIRE_MINIGAME_TICKS =
        ConfigKey.integer("generators", "wireMinigameTicks", 200, 20, 1200);

    public static final ConfigKey<Integer> FUEL_REQUIRED_PERCENT =
        ConfigKey.integer("generators", "fuelRequiredPercent", 200, 100, 500);

    public static final ConfigKey<Integer> HIGHLIGHT_DURATION_TICKS =
        ConfigKey.integer("generators", "highlightDurationTicks", 100, 20, 600);

    /**
     * Скільки тіків триває ВИБУХ генератора після проваленої міні-гри:
     * червоне світіння генератора, вогонь і дим на ньому та червоний
     * маркер, який бачать усі гравці й маньяк. Дизайн: 5 секунд = 100.
     */
    public static final ConfigKey<Integer> FAIL_FLASH_TICKS =
        ConfigKey.integer("generators", "failFlashTicks", 100, 20, 600);

    /**
     * Скільки % REPAIR-прогресу знімається при провалі міні-гри
     * (промах у "цілі" або невірний дріт у "дротах") — дизайн: 10%.
     * Знімається зі СПІЛЬНОГО прогресу генератора (не лише внеску
     * гравця, що провалив), і супроводжується
     * вибухом — див. {@link com.log_to_kot.maniacmod.map.zones.GeneratorPoi#explode}.
     * Бензин (FUEL) вибух не чіпає.
     */
    public static final ConfigKey<Integer> MINIGAME_FAIL_LOSS_PERCENT =
        ConfigKey.integer("generators", "minigameFailLossPercent", 10, 0, 100);

    /**
     * Найбільша відстань (блоків) від гравця до центру генератора, на якій
     * утримання Shift+ПКМ ще дає прогрес. Клієнт бачить генератор лише
     * з досяжності руки (~3 блоки), тож запас потрібен лише на затримку
     * мережі та розмір хітбокса; далі — сервер ставить ремонт на паузу.
     */
    public static final ConfigKey<Double> REPAIR_MAX_DISTANCE_BLOCKS =
        ConfigKey.decimal("generators", "repairMaxDistanceBlocks", 5.0, 1.0, 16.0);

    /**
     * Найбільший кут (градусів) між напрямом погляду гравця й напрямом
     * на генератор, поки ремонт іде. Гравець, що відвернувся, зі
     * затиснутими клавішами ремонтує не має. 180 вимикає перевірку.
     */
    public static final ConfigKey<Integer> REPAIR_MAX_LOOK_ANGLE_DEGREES =
        ConfigKey.integer("generators", "repairMaxLookAngleDegrees", 60, 10, 180);

    /**
     * Допуск на затримку мережі для перевірки кліку в міні-грі «ціль»,
     * мілісекунд. Клієнт рахує положення повзунка за власним годинником, і
     * сервер бачить клік із запізненням. Сервер перераховує траєкторію
     * повзунка сам і приймає лише таку позицію, яку повзунок реально мав
     * у вікні [зараз − допуск, зараз]; вигадану позицію відкидає.
     */
    public static final ConfigKey<Integer> TARGET_MINIGAME_LAG_TOLERANCE_MS =
        ConfigKey.integer("generators", "targetMinigameLagToleranceMs", 400, 50, 2000);

    /**
     * Скільки секунд суцільного утримання ПКМ потрібно, щоб пройти
     * стадію REPAIR з 0% до 100% (без інструментів-бонусів). Пряма
     * заміна колишньої пари MINIGAMES_PER_GENERATOR×MINIGAME_TICKS —
     * тепер один параметр керує швидкістю простого лагодження.
     */
    public static final ConfigKey<Integer> REPAIR_SECONDS_PER_STAGE =
        ConfigKey.integer("generators", "repairSecondsPerStage", 90, 1, 600);

    /**
     * Швидкість заливки бензину: скільки ВІДСОТКІВ палива за одну секунду
     * суцільного утримання Shift+ПКМ переходить із каністри в генератор.
     *
     * ── Одне число на обидві сторони ──────────────────────────────────
     * Заливка йде 1 до 1: стільки відсотків, скільки додалось генератору
     * (0…FUEL_REQUIRED_PERCENT), стільки ж списується із заряду каністри
     * в руці (0…100%). Окремого конфіга "витрата каністри" немає й не
     * має бути — інакше швидкість заливки й витрати могли б розійтись.
     *
     * НЕ плутати з {@link #REPAIR_SECONDS_PER_STAGE}: та керує лише
     * стадією REPAIR і виражена в секундах на стадію, а тут — прямий темп
     * у відсотках за секунду, як просив дизайн.
     *
     * Дефолт 2: повна каністра (100%) спорожнюється за 50 с, а генератор
     * (200%) заливається за 100 с.
     */
    public static final ConfigKey<Integer> FUEL_PERCENT_PER_SECOND =
        ConfigKey.integer("generators", "fuelPercentPerSecond", 2, 1, 100);

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

    /**
     * Мінімальна висота падіння (блоки), з якої нога МОЖЕ зламатись.
     * Між {@link #FALL_KNOCKDOWN_HEIGHT} і цим значенням гравець лише
     * лягає, але нога ціла. Було: один фіксований шанс на кожне падіння
     * від порога нокдауну — тому нога ламалась і з 4 блоків приблизно
     * щотретій раз, незалежно від висоти.
     */
    public static final ConfigKey<Integer> LEG_BREAK_MIN_HEIGHT =
        ConfigKey.integer("survivors", "legBreakMinHeightBlocks", 6, 2, 64);

    /** Шанс зламати ногу рівно на {@link #LEG_BREAK_MIN_HEIGHT} (0..1). */
    public static final ConfigKey<Double> LEG_BREAK_CHANCE_AT_MIN =
        ConfigKey.decimal("survivors", "legBreakChanceAtMinHeight", 0.20, 0.0, 1.0);

    /** Висота, з якої шанс перелому вже максимальний і далі не росте. */
    public static final ConfigKey<Integer> LEG_BREAK_MAX_HEIGHT =
        ConfigKey.integer("survivors", "legBreakMaxHeightBlocks", 10, 2, 64);

    /** Шанс зламати ногу на {@link #LEG_BREAK_MAX_HEIGHT} і вище (0..1). */
    public static final ConfigKey<Double> LEG_BREAK_CHANCE_AT_MAX =
        ConfigKey.decimal("survivors", "legBreakChanceAtMaxHeight", 0.90, 0.0, 1.0);

    /**
     * Скільки натискань пробілу треба, щоб підвестися після падіння
     * (стан CRAWLING). Раніше це була константа-літерал у
     * SurvivorModule зі значенням 1 — тобто прогресу не існувало
     * взагалі: перше ж натискання відразу піднімало гравця, а шкали
     * вставання не було чого показувати. Тепер це число дизайну.
     */
    public static final ConfigKey<Integer> STAND_UP_PRESSES =
        ConfigKey.integer("survivors", "standUpPresses", 10, 1, 100);

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

    /**
     * Скільки тіків щойно КИНУТИЙ (Q) предмет не можна підібрати назад.
     * Без цього гравець, що кидає предмет собі під ноги, підбирає його
     * тим самим кліком, яким кидав, — і кидок виглядає зламаним.
     */
    public static final ConfigKey<Integer> GROUND_ITEM_DROP_PICKUP_DELAY_TICKS =
        ConfigKey.integer("loot", "dropPickupDelayTicks", 20, 0, 200);

    /**
     * Чи малювати білі блискітки (END_ROD) навколо предмета на землі.
     *
     * <p>Блиск малює КЛІЄНТ ({@code GroundItemEntity.tickClient}), тому
     * сам ключ читає сервер ({@link ManiacConfigs}) і розсилає значення
     * пакетом при вході гравця й на {@code /maniac reload} — клієнт
     * ніколи не читає {@code maniac.yml} напряму. Див.
     * {@code net.s2c.loot.GroundItemVisualSettingsPacket}.</p>
     */
    public static final ConfigKey<Boolean> GROUND_ITEM_SPARKLE_ENABLED =
        ConfigKey.bool("loot", "sparkleEnabled", true);

    // ── inventory ────────────────────────────────────────────────────────

    /**
     * Чи звільняти гравців у CREATIVE/SPECTATOR від обмежень слотів і
     * приховування хотбару.
     *
     * <p>{@code true} (дефолт) — креатив і спостереження виводяться за межі
     * правил. У маніяку креатив — інструмент адміна/картобудівника, а не
     * ігровий режим: гравці ходять в ADVENTURE, тож обмеження на них і так
     * діють, а адмін у креативі не мусить боротися з 0/4 слотами.</p>
     *
     * <p>{@code false} — правила ДІЮТЬ для всіх режимів, включно з
     * креативом: адмін бачить ті самі 0/4 слоти, що й гравець. Потрібно,
     * коли треба перевірити розкладку клавіш і хотбар з адмінського акаунта
     * або коли креатив використовується у самій грі.</p>
     */
    public static final ConfigKey<Boolean> INVENTORY_BYPASS_CREATIVE =
        ConfigKey.bool("inventory", "bypassCreative", true);

    // ── Реєстр блоків ────────────────────────────────────────────────────

    /**
     * Головний блок — тривалості технічних фаз і мінімум гравців.
     * Живе у ГОЛОВНОМУ файлі {@code maniac.yml} (решта блоків — другорядні,
     * кожен у своєму файлі: survivors.yml, maniacs.yml, points.yml...).
     */
    public static final ConfigBlock GAME = ConfigBlock.settings("game", FILE_NAME,
        "Головні правила матчу: мінімум гравців, тривалості технічних фаз "
        + "і дебаг-режим для одиночних перевірок.",
        MIN_PLAYERS, ROLE_REVEAL_SECONDS, ENDING_SECONDS, DEBUG_MODE, DEBUG_ROLE);

    public static final ConfigBlock WORLD = ConfigBlock.settings("world", "world.yml",
        "Час доби (в усіх фазах) і керовані погодні події під час гри. "
        + "У лобі погоди немає завжди.",
        GAME_TIME_TICKS, MAINTAIN_GAME_TIME,
        WEATHER_EVENTS_ENABLED, WEATHER_EVENT_CHANCE, WEATHER_TARGET_SHARE,
        WEATHER_MIN_INTERVAL_SECONDS, WEATHER_MAX_INTERVAL_SECONDS,
        WEATHER_MIN_DURATION_SECONDS, WEATHER_MAX_DURATION_SECONDS,
        WEATHER_THUNDER_CHANCE);

    public static final ConfigBlock MAP = ConfigBlock.settings("map", "points.yml",
        "Розмір карти й правила розкидання гравців.",
        MAP_SIZE_BLOCKS, MIN_SURVIVOR_TO_MANIAC, MIN_SURVIVOR_TO_PEER,
        ITEM_POINT_ENGAGE_RATIO, RELAXATION_STEP, MAX_RELAXATION_PASSES);

    public static final ConfigBlock GENERATORS = ConfigBlock.settings("generators", "generators.yml",
        "Ремонт, бензин, міні-ігри й підсвітка генераторів.",
        GENERATORS_REQUIRED, BONUS_GENERATORS_PER_MANIAC,
        MINIGAME_TRIGGER_CHANCE_PER_TICK,
        TARGET_MINIGAME_HITS_REQUIRED, TARGET_MINIGAME_CURSOR_SPEED, TARGET_MINIGAME_HIT_ZONE_WIDTH,
        WIRE_MINIGAME_TICKS, TARGET_MINIGAME_LAG_TOLERANCE_MS,
        FUEL_REQUIRED_PERCENT, FUEL_PERCENT_PER_SECOND,
        HIGHLIGHT_DURATION_TICKS, FAIL_FLASH_TICKS, MINIGAME_FAIL_LOSS_PERCENT,
        REPAIR_SECONDS_PER_STAGE, REPAIR_MAX_DISTANCE_BLOCKS, REPAIR_MAX_LOOK_ANGLE_DEGREES,
        MIN_GENERATOR_TO_PEER, MIN_GENERATOR_TO_MANIAC,
        SCREWDRIVER_SPEED_BONUS,
        SCREWDRIVER_MINIGAME_REDUCTION, GENERATOR_STAGE_ORDER);

    public static final ConfigBlock SURVIVORS = ConfigBlock.settings("survivors", "survivors.yml",
        "Здоров'я, стаміна, падіння, підняття непритомних.",
        SURVIVOR_MAX_HP, SURVIVOR_SLOTS, FLASHLIGHT_COOLDOWN_TICKS, RESCUE_TICKS,
        RESCUE_HELPER_BONUS, STAMINA_DRAIN_PER_TICK, STAMINA_REGEN_PER_TICK,
        FALL_KNOCKDOWN_HEIGHT, LEG_BREAK_MIN_HEIGHT, LEG_BREAK_CHANCE_AT_MIN,
        LEG_BREAK_MAX_HEIGHT, LEG_BREAK_CHANCE_AT_MAX, STAND_UP_PRESSES, HEARTBEAT_RANGE_BLOCKS);

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
        GROUND_ITEM_PICKUP_RANGE, GROUND_ITEM_DROP_PICKUP_DELAY_TICKS, GROUND_ITEM_SPARKLE_ENABLED);

    public static final ConfigBlock INVENTORY = ConfigBlock.settings("inventory", "inventory.yml",
        "Скільки слотів хотбару бачить кожна роль і чи діють правила в креативі.",
        INVENTORY_BYPASS_CREATIVE);

    public static final ConfigBlock SPAWN_POINTS = ConfigBlock.data("spawn_points",
        "Розмітка точок. Наповнюється командою /maniac point add. "
        + "Вміст ніколи не відновлюється з дефолту — видалена точка лишається видаленою.");

    public static final ConfigBlock ZONES = ConfigBlock.data("zones",
        "Зони карти (втеча, лут). Наповнюється командами. "
        + "Вміст ніколи не відновлюється з дефолту.");

    /** Порядок = порядок блоків у згенерованому файлі. */
    public static final List<ConfigBlock> BLOCKS = List.of(
        GAME, WORLD, MAP, GENERATORS, SURVIVORS, MANIAC, MANIAC_SELECTION, LOOT, INVENTORY);

    public static ConfigBlock blockById(String id) {
        for (ConfigBlock block : BLOCKS) {
            if (block.id().equals(id)) return block;
        }
        return null;
    }
}
