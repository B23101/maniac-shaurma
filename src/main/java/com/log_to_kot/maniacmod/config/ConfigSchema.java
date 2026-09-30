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
 * ── Блоки, яких тут немає: параметри маньяка ─────────────────────────
 * Блок {@code maniac_stats_<id>} (файл {@code maniac_stats/<id>.yml})
 * оголошує не цей клас, а сам архетип — див. {@code ManiacStats}. Це
 * єдиний виняток із правила «схема в одному файлі», і він вимушений:
 * набір ключів залежить від того, які маньяки зареєстровані кодом.
 * Правила ті самі — діапазони, дефолти, лікування, діагностика, — бо
 * ключі будуються тими самими фабриками {@link ConfigKey}.
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

    // ── Кіно-вступ (CINEMATIC): Replay-камера + loader з порадами ─────────

    /** Вимкнути — CINEMATIC знову живе один тік, матч стартує миттєво (як до вступу). */
    public static final ConfigKey<Boolean> INTRO_ENABLED =
        ConfigKey.bool("game", "introEnabled", true);

    /** Вікно Replay: рівно стільки секунд камера летить і йде loader; далі Replay обривається. */
    public static final ConfigKey<Integer> INTRO_REPLAY_SECONDS =
        ConfigKey.integer("game", "introReplaySeconds", 10, 3, 60);

    /** Як часто на loader-екрані змінюється порада. */
    public static final ConfigKey<Integer> INTRO_TIP_SECONDS =
        ConfigKey.integer("game", "introTipSeconds", 5, 2, 30);

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
     * для КОЖНОГО гравця, що зараз лагодить цей генератор — кому саме
     * випала міні-гра, вирішується по кожному окремо (гра відкривається
     * лише йому), але сам ремонт генератора на цей час ЗУПИНЯЄТЬСЯ для
     * всіх (див. {@link #MINIGAME_BLOCKS_REPAIR}). Малий дефолт
     * навмисно: при декількох гравцях на одному генераторі сумарний
     * шанс за секунду росте пропорційно їхній кількості.
     */
    public static final ConfigKey<Double> MINIGAME_TRIGGER_CHANCE_PER_TICK =
        ConfigKey.decimal("generators", "minigameTriggerChancePerTick", 0.0015, 0.0, 1.0);

    /**
     * Скільки тіків від ПОЧАТКУ сесії ремонту (перший {@code refreshRepair}
     * на цей генератор, не кожне повторне утримання) міні-гра гарантовано
     * НЕ випадає — щоб вона не могла з'явитись за секунду після 0%, як у
     * Dead by Daylight (там перший скілл-чек теж не трапляється миттєво).
     * Не скидається паузами: якщо гравець відпустив і знову затиснув ПКМ
     * на той самий генератор, лишок grace-періоду didn't reset — рахунок
     * іде від {@link com.log_to_kot.maniacmod.map.GeneratorModule.RepairSession#pos}
     * створення сесії, а не від кожного відновлення утримання.
     */
    public static final ConfigKey<Integer> MINIGAME_GRACE_TICKS =
        ConfigKey.integer("generators", "minigameGraceTicks", 60, 0, 1200);

    /**
     * Скільки тіків після завершення міні-гри (успіх чи провал) наступна
     * міні-гра гарантовано не випаде — той самий cooldown, що в DBD між
     * скілл-чеками, щоб вони не йшли одна за одною занадто щільно.
     */
    public static final ConfigKey<Integer> MINIGAME_COOLDOWN_TICKS =
        ConfigKey.integer("generators", "minigameCooldownTicks", 100, 0, 1200);

    /**
     * Чи блокує міні-гра ремонт генератора для ВСІХ гравців (дефолт —
     * так). Поки хтось розбирається з міні-грою, решта не може рухати
     * прогрес цього генератора його утриманням ПКМ: замість «кожен сам
     * за себе» генератор стає спільною точкою, яку не можна протягнути
     * повз міні-гру силою інших рук. Решта гравців отримують
     * actionbar-повідомлення, а самого гравця з міні-грою це блокування
     * не чіпає (його внесок і без того заморожений, поки гра не
     * скінчиться).
     *
     * Вимкнено ({@code false}) — стара поведінка: внесок заморожується
     * лише в того, кому випала міні-гра, а інші лагодять як завжди.
     */
    public static final ConfigKey<Boolean> MINIGAME_BLOCKS_REPAIR =
        ConfigKey.bool("generators", "minigameBlocksRepair", true);

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
        ConfigKey.decimal("generators", "targetMinigameCursorSpeed", 1.1, 0.05, 5.0);

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
     * Дефолт 4 (раніше було 2 — залив пришвидшено вдвічі): повна
     * каністра (100%) спорожнюється за 25 с, а генератор (200%)
     * заливається за 50 с.
     */
    public static final ConfigKey<Integer> FUEL_PERCENT_PER_SECOND =
        ConfigKey.integer("generators", "fuelPercentPerSecond", 4, 1, 200);

    /**
     * Бонус швидкості ремонту генератора, поки виживий тримає викрутку в
     * головній руці: множник = 1 + бонус. Дефолт 1.0 → ремонт вдвічі
     * швидший (2 тіки прогресу за тік). Діє лише на стадії REPAIR.
     */
    public static final ConfigKey<Double> SCREWDRIVER_SPEED_BONUS =
        ConfigKey.decimal("generators", "screwdriverSpeedBonus", 1.0, 0.0, 5.0);

    /**
     * Скільки відсотків міцності викрутка втрачає за кожну секунду, поки
     * нею реально лагодять генератор (5 → повна викрутка служить 20 с).
     */
    public static final ConfigKey<Integer> SCREWDRIVER_WEAR_PERCENT_PER_SECOND =
        ConfigKey.integer("generators", "screwdriverWearPercentPerSecond", 5, 0, 100);

    public static final ConfigKey<Double> SCREWDRIVER_MINIGAME_REDUCTION =
        ConfigKey.decimal("generators", "screwdriverMinigameReduction", 0.25, 0.0, 1.0);

    /**
     * Порядок стадій генератора. Типово спершу заливається бензин, і лише
     * потім генератор лагодиться (з міні-іграми). Читається один раз при
     * створенні генератора — див. {@code GeneratorPoi}.
     */
    public static final ConfigKey<String> GENERATOR_STAGE_ORDER =
        ConfigKey.option("generators", "stageOrder", "FUEL_THEN_REPAIR",
            List.of("REPAIR_THEN_FUEL", "FUEL_THEN_REPAIR"));

    // ── survivors ────────────────────────────────────────────────────────

    public static final ConfigKey<Integer> SURVIVOR_MAX_HP =
        ConfigKey.integer("survivors", "maxHp", 100, 1, 1000);

    public static final ConfigKey<Integer> SURVIVOR_SLOTS =
        ConfigKey.integer("survivors", "inventorySlots", 4, 1, 9);

    public static final ConfigKey<Integer> FLASHLIGHT_COOLDOWN_TICKS =
        ConfigKey.integer("survivors", "flashlightCooldownTicks", 600, 20, 6000);

    /**
     * Скільки тіків треба утримувати ПКМ на непритомному, щоб підняти його
     * (одним рятівником). 160 тіків = 8 с.
     */
    public static final ConfigKey<Integer> RESCUE_TICKS =
        ConfigKey.integer("survivors", "rescueTicks", 160, 20, 6000);

    /**
     * Скільки тіків непритомний живе без допомоги, перш ніж помре.
     * 1200 тіків = 60 с. Час іде лише в ігрових фазах і стоїть, поки
     * гравець офлайн.
     */
    public static final ConfigKey<Integer> DOWNED_BLEED_OUT_TICKS =
        ConfigKey.integer("survivors", "downedBleedOutTicks", 1200, 100, 36000);

    /**
     * За скільки тіків ПОВНИЙ прогрес підняття згасає до нуля, коли його
     * ніхто не тримає (100 тіків = 5 с). Швидкість згасання стала — від
     * неповного прогресу він скидається відповідно швидше.
     */
    public static final ConfigKey<Integer> RESCUE_DECAY_TICKS =
        ConfigKey.integer("survivors", "rescueDecayTicks", 100, 1, 6000);

    /** Скільки хп має гравець одразу після підняття (обмежується максимумом ролі). */
    public static final ConfigKey<Integer> REVIVE_HP =
        ConfigKey.integer("survivors", "reviveHp", 10, 1, 1000);

    /**
     * Частка звичайної швидкості, з якою повзе непритомний (0.15 = 15%).
     * Множник ідеться як атрибутний модифікатор на сервері, тож клієнт
     * отримує його разом зі стандартною синхронізацією атрибутів.
     */
    public static final ConfigKey<Double> DOWNED_CRAWL_SPEED =
        ConfigKey.decimal("survivors", "downedCrawlSpeed", 0.15, 0.0, 1.0);

    /**
     * На якій відстані (блоки, від ніг до ніг) рятівник може піднімати
     * непритомного. Трохи більше за ванільну дальність рейкасту (3.0):
     * рейкаст міряє від ОЧЕЙ до хітбокса, а тут — між позиціями, тож при
     * 3.0 крайні влучання клієнта сервер відхиляв би.
     */
    public static final ConfigKey<Double> RESCUE_RANGE_BLOCKS =
        ConfigKey.decimal("survivors", "rescueRangeBlocks", 3.5, 1.0, 8.0);

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
     * Мінімальна висота падіння (блоки), з якої ноги вже зношуються.
     * Навіть падіння НИЖЧЕ порога нокдауну ({@link #FALL_KNOCKDOWN_HEIGHT})
     * не безкарне: воно зменшує приховану міцність ніг. Було: нижче порога
     * не траплялось нічого взагалі. 0 блоків не має сенсу (кожен стрибок
     * був би падінням), тому мінімум 1.
     */
    public static final ConfigKey<Integer> FALL_LEG_WEAR_MIN_HEIGHT =
        ConfigKey.integer("survivors", "fallLegWearMinHeightBlocks", 2, 1, 64);

    /**
     * Скільки ПРИХОВАНОЇ міцності ніг (0–100) знімає кожен повний блок
     * висоти падіння. Діє на будь-яке падіння від
     * {@link #FALL_LEG_WEAR_MIN_HEIGHT}, а не лише на нокдаун. Коли шкала
     * доходить до нуля — нога ламається. Міцність повільно відновлюється
     * ({@code legIntegrityRegenPerSecond}). 0 — падіння не зношує ноги.
     */
    public static final ConfigKey<Double> FALL_LEG_WEAR_PER_BLOCK =
        ConfigKey.decimal("survivors", "fallLegWearPerBlock", 8.0, 0.0, 100.0);

    /**
     * Скільки натискань пробілу треба, щоб підвестися після падіння
     * (стан CRAWLING). Раніше це була константа-літерал у
     * SurvivorModule зі значенням 1 — тобто прогресу не існувало
     * взагалі: перше ж натискання відразу піднімало гравця, а шкали
     * вставання не було чого показувати. Тепер це число дизайну.
     */
    public static final ConfigKey<Integer> STAND_UP_PRESSES =
        ConfigKey.integer("survivors", "standUpPresses", 25, 1, 200);

    /**
     * За скільки тіків повний прогрес вставання згасає до нуля, якщо
     * пробіл не спамити (80 тіків = 4 с). Швидкість згасання стала: від
     * неповного прогресу він скидається відповідно швидше. При
     * {@code standUpPresses = 25} це згасання ≈0.31/тік, тобто встати
     * можна лише тиснучи частіше ніж ~6 разів на секунду — саме
     * «спамити пробіл», а не «натиснути N разів».
     */
    public static final ConfigKey<Integer> STAND_UP_DECAY_TICKS =
        ConfigKey.integer("survivors", "standUpDecayTicks", 80, 1, 6000);

    public static final ConfigKey<Integer> HEARTBEAT_RANGE_BLOCKS =
        ConfigKey.integer("survivors", "heartbeatRangeBlocks", 12, 0, 128);

    /**
     * На якій відстані (блоки) маньяк вважається «надто близько» до
     * непритомного: поки він у цьому радіусі, таймер до смерті СТОЇТЬ,
     * а маньяку в actionbar приходить підказка відійти. Так добити
     * лежачого можна лише перечекавши, а не стоячи над ним.
     */
    public static final ConfigKey<Integer> MANIAC_MERCY_RADIUS_BLOCKS =
        ConfigKey.integer("survivors", "maniacMercyRadiusBlocks", 15, 0, 64);

    /**
     * Скільки тіків тримається ефект швидкості I на виживому після того,
     * як його вдарив маньяк (100 тіків = 5 с). Стаміна при ударі
     * відновлюється повністю — тому поранений може рвонути геть.
     */
    public static final ConfigKey<Integer> MANIAC_HIT_SPEED_TICKS =
        ConfigKey.integer("survivors", "maniacHitSpeedTicks", 100, 0, 6000);

    /**
     * Скільки тіків діє адреналін (предмет «Адреналін»): виживий біжить
     * швидше, а стаміна не витрачається (200 тіків = 10 с).
     */
    public static final ConfigKey<Integer> ADRENALINE_DURATION_TICKS =
        ConfigKey.integer("survivors", "adrenalineDurationTicks", 200, 20, 6000);

    /** Рівень ефекту швидкості під адреналіном: 0 = Speed I, 1 = Speed II. */
    public static final ConfigKey<Integer> ADRENALINE_SPEED_AMPLIFIER =
        ConfigKey.integer("survivors", "adrenalineSpeedAmplifier", 1, 0, 5);

    /**
     * «Відкат» після адреналіну: стільки тіків стаміна тримається на нулі
     * й не відновлюється (100 тіків = 5 с).
     */
    public static final ConfigKey<Integer> ADRENALINE_CRASH_TICKS =
        ConfigKey.integer("survivors", "adrenalineCrashTicks", 100, 0, 6000);

    // ── maniac ───────────────────────────────────────────────────────────
    //
    // Тут НЕМАЄ ключів, які описують конкретного маньяка: дальність
    // удару, шкода, перезарядка, множник швидкості, габарити й висота
    // очей. Вони переїхали в блок КОЖНОГО маньяка (див. ManiacStats):
    // ключ, спільний на всіх, змушував би правити один файл, щоб
    // змінити одного, а різні маньяки за дизайном різні за всіма цими
    // числами. Те, що тут лишилось, — справді спільне для режиму
    // (розміщення пасток, лом, оглушення), бо воно не залежить від
    // того, ХТО саме полює.

    public static final ConfigKey<Integer> TRAP_PLACE_COOLDOWN_TICKS =
        ConfigKey.integer("maniac", "trapPlaceCooldownTicks", 500, 20, 6000);

    /**
     * Перезарядка ПІСЛЯ встановлення капкана, у тіках. 800 = 40 с.
     *
     * <p>Окремо від {@link #TRAP_PLACE_COOLDOWN_TICKS}: то спільне
     * значення для пасток без власного, а капкан за дизайном має свій
     * (він перевизначає {@code TrapArchetype#placementCooldownTicks()}).</p>
     */
    public static final ConfigKey<Integer> BEAR_TRAP_PLACE_COOLDOWN_TICKS =
        ConfigKey.integer("maniac", "bearTrapPlaceCooldownTicks", 800, 20, 12000);

    /**
     * На яку відстань (блоків) від очей маньяк може націлити розміщення
     * пастки. Сервер міряє це заново — позиція в пакеті лише «куди
     * дивиться клієнт», а не довірене значення.
     */
    public static final ConfigKey<Double> TRAP_PLACE_RANGE_BLOCKS =
        ConfigKey.decimal("maniac", "trapPlaceRangeBlocks", 6.0, 2.0, 16.0);

    /**
     * Скільки пасток маньяк бере в матч (вибір у меню на початку).
     * Верхня межа — {@code ManiacArchetype.MAX_TRAP_SLOTS} (клавіші
     * 5/6/7), тож більше трьох не має сенсу. Поки в реєстрі одна
     * пастка, вибір зводиться до неї — але число готове.
     */
    public static final ConfigKey<Integer> TRAPS_PER_MATCH =
        ConfigKey.integer("maniac", "trapsPerMatch", 3, 1, 3);

    // ── bear trap (капкан): наслідки для жертви ─────────────────────────

    /** Шкода від захлопнення капкана, у хп. */
    // ── Міні-гра визволення з капкана ───────────────────────────────────
    // Свої значення, а не ключі генератора: капкан і ремонт — різні за
    // відчуттям події (капкан карає за помилку не вибухом, а втратою
    // прогресу), тому адміністратор мусить мати змогу настроїти їх
    // незалежно. Спільною лишається тільки геометрія траєкторії —
    // код {@code TargetMinigameSpec}, а не конфіг.

    /** Скільки влучань треба, щоб вибратися з капкана власними силами. */
    public static final ConfigKey<Integer> TRAP_ESCAPE_HITS_REQUIRED =
        ConfigKey.integer("maniac", "trapEscapeHitsRequired", 5, 1, 20);

    /**
     * Скільки влучань ЗНИМАЄ промах (не нижче нуля). На відміну від
     * міні-гри генератора, де промах — це одразу провал із вибухом, тут
     * промах лише відкидає назад: капкан не вибухає й не карає гру
     * вибухом, а просто не відпускає. 0 вимикає штраф.
     */
    public static final ConfigKey<Integer> TRAP_ESCAPE_MISS_PENALTY =
        ConfigKey.integer("maniac", "trapEscapeMissPenalty", 2, 0, 10);

    /** Швидкість повзунка в міні-грі визволення з капкана (часток смуги за секунду). */
    public static final ConfigKey<Double> TRAP_ESCAPE_CURSOR_SPEED =
        ConfigKey.decimal("maniac", "trapEscapeCursorSpeed", 0.9, 0.05, 5.0);

    /** Ширина зони влучання в міні-грі визволення з капкана (часток смуги). */
    public static final ConfigKey<Double> TRAP_ESCAPE_HIT_ZONE_WIDTH =
        ConfigKey.decimal("maniac", "trapEscapeHitZoneWidth", 0.10, 0.01, 0.5);

    /**
     * Скільки тіків дано на спробу вибратися з капкана. Час вийшов —
     * екран закривається, гравець лишається в капкані й чекає на
     * товариша з ломом: спроба одна на захлопування (інакше капкан не
     * тримав би нікого — гравець просто чекав би вдалого раунду).
     */
    public static final ConfigKey<Integer> TRAP_ESCAPE_TICKS =
        ConfigKey.integer("maniac", "trapEscapeTicks", 300, 20, 1200);

    /**
     * Скільки тіків жертва бачить лише текстову підказку "Натисни F,
     * щоб вибратися" ПЕРЕД тим, як почнеться сама спроба
     * ({@link #TRAP_ESCAPE_TICKS}). Це вікно НЕ входить у час спроби:
     * повзунок і відлік спроби стартують лише в момент натискання F
     * (див. {@code TrapModule#onEscapeStart}), тож ці два ліміти не
     * складаються в один прихований довший тайм-аут — гравець, що
     * довго вагався перед F, просто пізніше побачить сам повзунок, а
     * не втрачає з цього часу спробу.
     *
     * Час вийшов, а F не натиснуто — та сама доля, що провал самої
     * міні-гри: гравець лишається в капкані й чекає на товариша з
     * ломом (спроба одна на захлопування).
     */
    public static final ConfigKey<Integer> TRAP_ESCAPE_PROMPT_TICKS =
        ConfigKey.integer("maniac", "trapEscapePromptTicks", 300, 20, 1200);

    public static final ConfigKey<Integer> BEAR_TRAP_DAMAGE =
        ConfigKey.integer("maniac", "bearTrapDamage", 15, 0, 100);

    /**
     * Скільки ПРИХОВАНОЇ міцності ніг (0–100) знімає один капкан.
     * Шкала клієнту не показується: гравець не знає, скільки ще
     * витримають ноги, доки не хруснуло. Кілька капканів поспіль
     * зламають ногу напевно, один — лише на межі.
     */
    public static final ConfigKey<Integer> BEAR_TRAP_LEG_DAMAGE =
        ConfigKey.integer("maniac", "bearTrapLegDamage", 40, 0, 100);

    /** Скільки міцності ніг відновлюється за секунду, коли гравець НЕ в пастці. 0 = не відновлюється. */
    public static final ConfigKey<Double> LEG_INTEGRITY_REGEN_PER_SECOND =
        ConfigKey.decimal("maniac", "legIntegrityRegenPerSecond", 0.5, 0.0, 20.0);

    /**
     * Скільки разів поспіль програється звук боротьби в захлопнутому
     * капкані. 0 — не грати взагалі.
     *
     * <p>Раніше звук повторювався БЕЗ КІНЦЯ, поки капкан когось тримав
     * ({@code TrapModule.tickStruggle}), — саме цей «вічний звук» і є
     * претензією з ТЗ. Тепер це скінченне число повторів після клацка:
     * капкан «покрутився» й затих, а не гуде до кінця матчу.</p>
     */
    public static final ConfigKey<Integer> BEAR_TRAP_STRUGGLE_REPEATS =
        ConfigKey.integer("maniac", "bearTrapStruggleRepeats", 3, 0, 64);

    // ── electric wire (електричний дріт) ────────────────────────────────
    // Новa пастка: маньяк вибирає ДВІ точки на одному Y, між ними без
    // перешкод, дріт ≤ 4 блоків. Торкання — удар струмом.

    /** Перезарядка після встановлення дроту, у тіках. 1200 = 60 с (ТЗ 9). */
    public static final ConfigKey<Integer> ELECTRIC_WIRE_PLACE_COOLDOWN_TICKS =
        ConfigKey.integer("maniac", "electricWirePlaceCooldownTicks", 1200, 20, 12000);

    /** Максимальна відстань між кріпленнями (блоків), ТЗ 2.1: 4. */
    public static final ConfigKey<Double> ELECTRIC_WIRE_MAX_LENGTH_BLOCKS =
        ConfigKey.decimal("maniac", "electricWireMaxLengthBlocks", 4.0, 1.0, 8.0);

    /** Скільки тіків діє удар струмом. 300 = 15 с (ТЗ 5.1). */
    public static final ConfigKey<Integer> ELECTRIC_WIRE_SHOCK_TICKS =
        ConfigKey.integer("maniac", "electricWireShockTicks", 300, 20, 2400);

    /** Скільки HP знімає удар струмом за КОЖНУ секунду дії (ТЗ 5.1: 1 HP/с). */
    public static final ConfigKey<Integer> ELECTRIC_WIRE_DAMAGE_PER_SECOND =
        ConfigKey.integer("maniac", "electricWireDamagePerSecond", 1, 0, 20);

    /** Амплітуда тряски камери під час удару струмом, градуси (ТЗ 5.2). */
    public static final ConfigKey<Double> ELECTRIC_WIRE_SHAKE_DEGREES =
        ConfigKey.decimal("maniac", "electricWireShakeDegrees", 2.5, 0.0, 15.0);

    /** Частота тряски камери, Гц (ТЗ 5.2). */
    public static final ConfigKey<Double> ELECTRIC_WIRE_SHAKE_HZ =
        ConfigKey.decimal("maniac", "electricWireShakeHz", 9.0, 0.5, 30.0);

    // ── rope bind (мотузка-підвіс) ──────────────────────────────────────
    // Одноразова пастка зі стелею 3–6 блоків: жертва висить догори дригом,
    // вибратися можна ножицями або розкачавшись (A/D) до розриву нитки.

    /** Перезарядка після встановлення мотузки, у тіках. 1000 = 50 с. */
    public static final ConfigKey<Integer> ROPE_TRAP_PLACE_COOLDOWN_TICKS =
        ConfigKey.integer("maniac", "ropeTrapPlaceCooldownTicks", 1000, 20, 12000);

    /** Мінімум вільних блоків між підлогою й стелею. */
    public static final ConfigKey<Integer> ROPE_TRAP_MIN_CEILING_BLOCKS =
        ConfigKey.integer("maniac", "ropeTrapMinCeilingBlocks", 3, 2, 8);

    /** Максимум вільних блоків між підлогою й стелею. */
    public static final ConfigKey<Integer> ROPE_TRAP_MAX_CEILING_BLOCKS =
        ConfigKey.integer("maniac", "ropeTrapMaxCeilingBlocks", 6, 3, 12);

    /** Сила одного вдалого натискання A/D (приріст кутової швидкості за натискання). */
    public static final ConfigKey<Double> ROPE_SWING_KICK =
        ConfigKey.decimal("maniac", "ropeSwingKick", 0.032, 0.005, 0.2);

    /** Згасання розгойдування за тік (0.020 = 2%): енергія спадає сама, тому це довго й муторно. */
    public static final ConfigKey<Double> ROPE_SWING_DAMPING =
        ConfigKey.decimal("maniac", "ropeSwingDamping", 0.020, 0.001, 0.2);

    /** «Жорсткість» маятника: більше — швидше гойдається (період ~ 2π/√k тіків). */
    public static final ConfigKey<Double> ROPE_SWING_FREQUENCY =
        ConfigKey.decimal("maniac", "ropeSwingFrequency", 0.05, 0.01, 0.2);

    /** Кут відхилення (градуси), за якого нитка рветься. */
    public static final ConfigKey<Double> ROPE_SWING_BREAK_DEGREES =
        ConfigKey.decimal("maniac", "ropeSwingBreakDegrees", 45.0, 10.0, 80.0);

    /**
     * Мінімальний інтервал між зарахованими натисканнями (тіків). Швидше —
     * промах: це і робить спам A/D марним, треба влучати в такт.
     */
    public static final ConfigKey<Integer> ROPE_SWING_PRESS_INTERVAL_TICKS =
        ConfigKey.integer("maniac", "ropeSwingPressIntervalTicks", 8, 2, 30);

    /** Дальність, з якої Geo-ножиці перерізають дріт (блоків від очей). */
    public static final ConfigKey<Double> GEO_SCISSORS_RANGE_BLOCKS =
        ConfigKey.decimal("maniac", "geoScissorsRangeBlocks", 3.5, 1.0, 8.0);

    // ── crowbar (лом) ────────────────────────────────────────────────────

    /** Скільки % міцності лом втрачає за один удар по капкану. Дизайн: 33. */
    public static final ConfigKey<Integer> CROWBAR_HIT_WEAR_PERCENT =
        ConfigKey.integer("maniac", "crowbarHitWearPercent", 33, 1, 100);

    /** Перезарядка лома між ударами по капкану, у тіках. 400 = 20 с. */
    public static final ConfigKey<Integer> CROWBAR_COOLDOWN_TICKS =
        ConfigKey.integer("maniac", "crowbarCooldownTicks", 400, 0, 12000);

    /** Дальність, з якої лом дістає до капкана (блоків від очей). */
    public static final ConfigKey<Double> CROWBAR_RANGE_BLOCKS =
        ConfigKey.decimal("maniac", "crowbarRangeBlocks", 3.5, 1.0, 8.0);

    // ── crowbar (лом): удар по маньяку, стан "оглушений" ─────────────────

    /**
     * Скільки тіків триває стан "оглушений маньяк" після влучання ломом.
     * 160 = 8 с (МІНІМУМ за дизайном: коротше оглушення робить удар ломом
     * майже безкарним для маньяка). Протягом цього часу маньяк не може
     * рухатись, стрибати, повертати камеру, бити, ставити пастки й
     * активувати здібності — і РІВНО стільки ж триває нудота.
     *
     * <p>Нижня межа прибита до 160, а не залишена вільною: значення менше
     * за 8 с суперечить самому сенсу стану (див. вище), тож це свідомо не
     * "гнучка настройка", а захист від випадкового послаблення.</p>
     */
    public static final ConfigKey<Integer> CROWBAR_STUN_DURATION_TICKS =
        ConfigKey.integer("maniac", "crowbarStunDurationTicks", 160, 160, 600);

    /**
     * Перезарядка лома між ударами ПО МАНЬЯКУ, у тіках. Окремо від
     * {@link #CROWBAR_COOLDOWN_TICKS} (той — для ударів по капкану):
     * оглушення маньяка — значно сильніший ефект, тому перезарядка
     * довша за замовчуванням і налаштовується незалежно.
     */
    public static final ConfigKey<Integer> CROWBAR_STUN_COOLDOWN_TICKS =
        ConfigKey.integer("maniac", "crowbarStunCooldownTicks", 600, 0, 12000);

    /** Дальність, з якої лом дістає до маньяка (блоків від очей виживого). */
    public static final ConfigKey<Double> CROWBAR_STUN_RANGE_BLOCKS =
        ConfigKey.decimal("maniac", "crowbarStunRangeBlocks", 3.5, 1.0, 8.0);

    /**
     * Скільки % міцності лом втрачає за удар по маньяку. Окремий від
     * {@link #CROWBAR_HIT_WEAR_PERCENT} (удар по капкану) — оглушення
     * маньяка є сильнішою дією, тому дизайн дозволяє інший знос.
     */
    public static final ConfigKey<Integer> CROWBAR_STUN_HIT_WEAR_PERCENT =
        ConfigKey.integer("maniac", "crowbarStunHitWearPercent", 25, 1, 100);

    /**
     * Рівень ефекту нудоти (ванільний {@code MobEffects.CONFUSION}),
     * що накладається на маньяка на час оглушення. 0 = amplifier I
     * (перший рівень) за дизайном "нудота 1".
     */
    public static final ConfigKey<Integer> CROWBAR_STUN_NAUSEA_AMPLIFIER =
        ConfigKey.integer("maniac", "crowbarStunNauseaAmplifier", 0, 0, 4);

    // ── bat (біта): удар по маньяку, стан "оглушений" ────────────────────
    // Біта — «старший брат» лома в частині оглушення: б'є по маньяку так
    // само (ManiacStunModule), але оглушує ТРОХИ ДОВШЕ й коштує 50% міцності
    // за удар (тобто 2 удари й вона ламається). По капканах біта не працює.
    // Перезарядка — ВЛАСНА (візуальний бар під предметом), не ванільна.

    /**
     * Тривалість оглушення маньяка бітою, у тіках. 240 = 12 с — трохи довше
     * за лом ({@link #CROWBAR_STUN_DURATION_TICKS}, 8 с). Нижня межа та сама,
     * що в лома: коротше за 8 с стан втрачає сенс.
     */
    public static final ConfigKey<Integer> BAT_STUN_DURATION_TICKS =
        ConfigKey.integer("maniac", "batStunDurationTicks", 240, 160, 600);

    /** Перезарядка біти між ударами по маньяку, у тіках. 600 = 30 с. */
    public static final ConfigKey<Integer> BAT_STUN_COOLDOWN_TICKS =
        ConfigKey.integer("maniac", "batStunCooldownTicks", 600, 0, 12000);

    /** Дальність, з якої біта дістає до маньяка (блоків від очей виживого). */
    public static final ConfigKey<Double> BAT_STUN_RANGE_BLOCKS =
        ConfigKey.decimal("maniac", "batStunRangeBlocks", 3.5, 1.0, 8.0);

    /** Скільки % міцності біта втрачає за удар по маньяку. Дизайн: 50. */
    public static final ConfigKey<Integer> BAT_STUN_HIT_WEAR_PERCENT =
        ConfigKey.integer("maniac", "batStunHitWearPercent", 50, 1, 100);

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

    // ── voice ────────────────────────────────────────────────────────────
    //
    // Голос маньяка (Simple Voice Chat). Ефект активує САМА гра за
    // роллю й фазою матчу — у гравця немає жодної ручки й жодного UI
    // для нього (див. voice/VoiceActivation). Тут лише те, що адмін
    // справді може хотіти змінити: чи вмикати ефект, який пресет і
    // наскільки він сильний.

    /**
     * Чи вмикати «жахливий» голос маньяка взагалі. Вимкнено — голос
     * маньяка звучить як звичайний, але решта голосових фаз (тиша,
     * спільний ефір) працюють так само.
     */
    public static final ConfigKey<Boolean> VOICE_ENABLED =
        ConfigKey.bool("voice", "enabled", true);

    /**
     * Пресет голосу. DEMON — “одержимий”, TITAN — “велетень у залі”,
     * BATMAN — “на октаву нижче, тісна кімната”. Числа приховуються в
     * {@code voice/ManiacVoiceProfile}, тут лише вибір.
     */
    public static final ConfigKey<String> VOICE_PROFILE =
        ConfigKey.option("voice", "profile", "DEMON",
            List.of("DEMON", "TITAN", "BATMAN"));

    /**
     * Сила ефекту, 0..100. 0 — голос майже не змінюється, 100 — повний
     * пресет. Проміжні значення інтерполюються до нейтрального профілю,
     * тому перехід плавний і без службових артефактів.
     */
    public static final ConfigKey<Integer> VOICE_STRENGTH =
        ConfigKey.integer("voice", "strength", 100, 0, 100);

    // ── Реєстр блоків ────────────────────────────────────────────────────

    /**
     * Головний блок — тривалості технічних фаз і мінімум гравців.
     * Живе у ГОЛОВНОМУ файлі {@code maniac.yml} (решта блоків — другорядні,
     * кожен у своєму файлі: survivors.yml, maniacs.yml, points.yml...).
     */
    public static final ConfigBlock GAME = ConfigBlock.settings("game", FILE_NAME,
        "Головні правила матчу: мінімум гравців, тривалості технічних фаз "
        + "і дебаг-режим для одиночних перевірок.",
        MIN_PLAYERS, INTRO_ENABLED, INTRO_REPLAY_SECONDS, INTRO_TIP_SECONDS,
        ROLE_REVEAL_SECONDS, ENDING_SECONDS, DEBUG_MODE, DEBUG_ROLE);

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
        MINIGAME_TRIGGER_CHANCE_PER_TICK, MINIGAME_GRACE_TICKS, MINIGAME_COOLDOWN_TICKS,
        MINIGAME_BLOCKS_REPAIR,
        TARGET_MINIGAME_HITS_REQUIRED, TARGET_MINIGAME_CURSOR_SPEED, TARGET_MINIGAME_HIT_ZONE_WIDTH,
        WIRE_MINIGAME_TICKS, TARGET_MINIGAME_LAG_TOLERANCE_MS,
        FUEL_REQUIRED_PERCENT, FUEL_PERCENT_PER_SECOND,
        HIGHLIGHT_DURATION_TICKS, FAIL_FLASH_TICKS, MINIGAME_FAIL_LOSS_PERCENT,
        REPAIR_SECONDS_PER_STAGE, REPAIR_MAX_DISTANCE_BLOCKS, REPAIR_MAX_LOOK_ANGLE_DEGREES,
        MIN_GENERATOR_TO_PEER, MIN_GENERATOR_TO_MANIAC,
        SCREWDRIVER_SPEED_BONUS, SCREWDRIVER_WEAR_PERCENT_PER_SECOND,
        SCREWDRIVER_MINIGAME_REDUCTION, GENERATOR_STAGE_ORDER);

    public static final ConfigBlock SURVIVORS = ConfigBlock.settings("survivors", "survivors.yml",
        "Здоров'я, стаміна, падіння, підняття непритомних.",
        SURVIVOR_MAX_HP, SURVIVOR_SLOTS, FLASHLIGHT_COOLDOWN_TICKS, RESCUE_TICKS,
        DOWNED_BLEED_OUT_TICKS, RESCUE_DECAY_TICKS, REVIVE_HP, DOWNED_CRAWL_SPEED, RESCUE_RANGE_BLOCKS,
        RESCUE_HELPER_BONUS, STAMINA_DRAIN_PER_TICK, STAMINA_REGEN_PER_TICK,
        FALL_KNOCKDOWN_HEIGHT, FALL_LEG_WEAR_MIN_HEIGHT, FALL_LEG_WEAR_PER_BLOCK,
        LEG_BREAK_MIN_HEIGHT, LEG_BREAK_CHANCE_AT_MIN,
        LEG_BREAK_MAX_HEIGHT, LEG_BREAK_CHANCE_AT_MAX, STAND_UP_PRESSES, STAND_UP_DECAY_TICKS,
        HEARTBEAT_RANGE_BLOCKS, MANIAC_MERCY_RADIUS_BLOCKS, MANIAC_HIT_SPEED_TICKS,
        ADRENALINE_DURATION_TICKS, ADRENALINE_SPEED_AMPLIFIER, ADRENALINE_CRASH_TICKS);

    public static final ConfigBlock MANIAC = ConfigBlock.settings("maniac", "maniacs.yml",
        "Спільні для всіх маньяків правила полювання.",
        TRAP_PLACE_COOLDOWN_TICKS,
        BEAR_TRAP_PLACE_COOLDOWN_TICKS,
        TRAP_PLACE_RANGE_BLOCKS, TRAPS_PER_MATCH,
        CROWBAR_HIT_WEAR_PERCENT, CROWBAR_COOLDOWN_TICKS, CROWBAR_RANGE_BLOCKS,
        TRAP_ESCAPE_HITS_REQUIRED, TRAP_ESCAPE_MISS_PENALTY,
        TRAP_ESCAPE_CURSOR_SPEED, TRAP_ESCAPE_HIT_ZONE_WIDTH, TRAP_ESCAPE_TICKS,
        TRAP_ESCAPE_PROMPT_TICKS,
        BEAR_TRAP_DAMAGE, BEAR_TRAP_LEG_DAMAGE, BEAR_TRAP_STRUGGLE_REPEATS, LEG_INTEGRITY_REGEN_PER_SECOND,
        ELECTRIC_WIRE_PLACE_COOLDOWN_TICKS, ELECTRIC_WIRE_MAX_LENGTH_BLOCKS,
        ELECTRIC_WIRE_SHOCK_TICKS, ELECTRIC_WIRE_DAMAGE_PER_SECOND,
        ELECTRIC_WIRE_SHAKE_DEGREES, ELECTRIC_WIRE_SHAKE_HZ,
        ROPE_TRAP_PLACE_COOLDOWN_TICKS, ROPE_TRAP_MIN_CEILING_BLOCKS, ROPE_TRAP_MAX_CEILING_BLOCKS,
        ROPE_SWING_KICK, ROPE_SWING_DAMPING, ROPE_SWING_FREQUENCY,
        ROPE_SWING_BREAK_DEGREES, ROPE_SWING_PRESS_INTERVAL_TICKS,
        GEO_SCISSORS_RANGE_BLOCKS,
        CROWBAR_STUN_DURATION_TICKS, CROWBAR_STUN_COOLDOWN_TICKS, CROWBAR_STUN_RANGE_BLOCKS,
        CROWBAR_STUN_HIT_WEAR_PERCENT, CROWBAR_STUN_NAUSEA_AMPLIFIER,
        BAT_STUN_DURATION_TICKS, BAT_STUN_COOLDOWN_TICKS, BAT_STUN_RANGE_BLOCKS,
        BAT_STUN_HIT_WEAR_PERCENT);

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

    public static final ConfigBlock VOICE = ConfigBlock.settings("voice", "voice.yml",
        "Голос маньяка через Simple Voice Chat: вмикання, пресет і сила ефекту. "
        + "Ефект керує гра за роллю й фазою матчу — у гравця немає налаштувань голосу.",
        VOICE_ENABLED, VOICE_PROFILE, VOICE_STRENGTH);

    public static final ConfigBlock SPAWN_POINTS = ConfigBlock.data("spawn_points",
        "Розмітка точок. Наповнюється командою /maniac point add. "
        + "Вміст ніколи не відновлюється з дефолту — видалена точка лишається видаленою.");

    public static final ConfigBlock ZONES = ConfigBlock.data("zones",
        "Зони карти (втеча, лут). Наповнюється командами. "
        + "Вміст ніколи не відновлюється з дефолту.");

    // ── darkzones ────────────────────────────────────────────────────────
    //
    // Розмітка самих темних зон (точки) — це DATA-блок ZONES вище
    // (файл zones.yml, наповнюється /maniac darkzone). Тут — лише СИЛА
    // ефекту, спільна для всіх темних зон карти: скільки max темнішає
    // lightmap і як плавно переходить край зони.

    /**
     * Найбільше зниження яскравості lightmap у темній зоні, 0..3.
     * Дефолт лишається 0.55 — "помітно темніше, ніж зараз, але гравець
     * ще нормально орієнтується". Верхню межу підняли з 1.0 до 3.0:
     * при 1.0 lightmap уже гаситься повністю (0 яскравості), тож щоб
     * мати запас для ЩЕ темніших налаштувань (напр. якщо
     * {@code DarkZoneEffect} у shaurma-lib згодом враховує значення
     * понад 1 — напр. для повільнішого "вигорання" світла ліхтарика
     * чи інтенсивнішого ефекту в комбінації з іншими джерелами) —
     * значення 1..3 більше не підтягуються (CLAMP) до 1.0.
     */
    public static final ConfigKey<Double> DARKZONE_INTENSITY =
        ConfigKey.decimal("darkzones", "intensity", 0.55, 0.0, 3.0);

    /**
     * За скільки тіків затемнення плавно доїжджає до цільового рівня
     * при вході в зону (і назад до нуля при виході) — щоб перетин межі
     * зони не виглядав різким клацанням lightmap. 20 тіків = 1 с.
     */
    public static final ConfigKey<Integer> DARKZONE_TRANSITION_TICKS =
        ConfigKey.integer("darkzones", "transitionTicks", 20, 0, 200);

    public static final ConfigBlock DARKZONES = ConfigBlock.settings("darkzones", "darkzones.yml",
        "Сила справжньої темряви (не оверлей — зниження яскравості освітлення) "
        + "у темних зонах, розмічених /maniac darkzone.",
        DARKZONE_INTENSITY, DARKZONE_TRANSITION_TICKS);

    /**
     * Порядок = порядок блоків у згенерованому файлі та порядок табів у
     * меню налаштувань. Список мутабельний ЛИШЕ заради
     * {@link #registerManiacBlock}: статичні блоки перелічені тут, а
     * блоки параметрів маньяків дописуються при реєстрації архетипів
     * (їх фізично не може бути в цьому рядку — вони з'являються разом із
     * класом маньяка). Чому всередині такого блока ключі теж не
     * статичні — див. док класу {@link ConfigBlock}.
     */
    public static final List<ConfigBlock> BLOCKS = new java.util.ArrayList<>(List.of(
        GAME, WORLD, MAP, GENERATORS, SURVIVORS, MANIAC, MANIAC_SELECTION,
        VOICE, LOOT, INVENTORY, DARKZONES));

    /**
     * Реєструє блок параметрів маньяка, ставлячи його одразу після
     * {@link #MANIAC} — так у меню налаштувань параметри йдуть поруч зі
     * спільними правилами полювання, а не в кінці списку через те, що
     * маньяка зареєстрували пізніше за лут.
     *
     * Викликається з {@code ManiacStats.ensureRegistered} під час
     * реєстрації архетипу, тобто ДО {@code ManiacConfigs.init}: перше ж
     * завантаження конфігу мусить бачити всі ключі, інакше файл маньяка
     * не створився б до наступного reload.
     */
    public static void registerManiacBlock(ConfigBlock block) {
        if (blockById(block.id()) != null) {
            throw new IllegalStateException("Блок конфігу '" + block.id() + "' уже зареєстрований.");
        }
        BLOCKS.add(BLOCKS.indexOf(MANIAC) + 1, block);
    }

    public static ConfigBlock blockById(String id) {
        for (ConfigBlock block : BLOCKS) {
            if (block.id().equals(id)) return block;
        }
        return null;
    }
}
