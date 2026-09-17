package com.log_to_kot.maniacmod.core.match;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.config.MapPointConfigs;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.core.phase.PhaseManager;
import com.log_to_kot.maniacmod.core.phase.Phases;
import com.log_to_kot.maniacmod.map.GeneratorModule;
import com.log_to_kot.maniacmod.map.zones.GeneratorPoi;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacCombatModule;
import com.log_to_kot.maniacmod.spawn.SpawnPlanner;
import com.log_to_kot.maniacmod.spawn.SpawnPointKind;
import com.log_to_kot.maniacmod.survivors.SurvivorRegistry;
import dev.shaurmalib.common.lobby.LobbySpawnPoint;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Диригент матчу. Замінює РОЛЬ КООРДИНАТОРА старого
 * game/ManiacGameManager.java — але саме роль, а не весь клас.
 *
 * ── Що сюди НЕ переїхало навмисно ────────────────────────────────────
 * Старий ManiacGameManager на 898 рядків робив одночасно:
 *   координацію матчу, тік пасток, бій, трупи/воскресіння, ремонт
 *   генераторів, видачу предметів, ефекти маньяка, broadcast у чат.
 * Через це будь-яка правка пасток ризикувала зачепити бій — рівно
 * та проблема "один кусок коду лізе в інший", з якої почалась
 * реорганізація.
 *
 * Тут лишається ЛИШЕ координація:
 *   хто маньяк, коли міняється фаза, коли матч закінчено.
 * Решта живе у своїх модулях і підписується через PhaseListener:
 *   traps/      — тік і розміщення пасток
 *   survivors/  — хп, стаміна, стани, підняття непритомних
 *   map/zones/  — генератори, виходи, зони втечі
 *   items/      — ефекти предметів
 *   abilities/  — здібності маньяка
 *
 * ── Ланцюжок фаз ─────────────────────────────────────────────────────
 * LOBBY → CINEMATIC → SCATTER → ROLE_REVEAL → HUNT → POWERED
 *       → FINALE → ENDING → RESET → LOBBY
 * Кожен перехід — один виклик advanceTo(...); умови переходу описані
 * в методах нижче, а не розмазані по всьому моду.
 */
public final class MatchOrchestrator {

    private final PhaseManager phases = new PhaseManager();
    private final Random rng = new Random();
    private final SpawnPlanner spawnPlanner = new SpawnPlanner(rng);

    private MatchContext context = new MatchContext();
    private MinecraftServer server;
    private SpawnPlanner.SpawnPlan pendingSpawnPlan;
    private boolean plannerReady;
    private boolean scatterApplied;
    private boolean scatterFailed;

    /** Точка спавну лобі. Задається командою /maniac lobby set. */
    private volatile LobbySpawnPoint lobbySpawn = new LobbySpawnPoint(0, 64, 0, 0f);

    /** Модуль генераторів. Тримається полем, бо блок генератора звертається до нього напряму. */
    private final GeneratorModule generators = new GeneratorModule(() -> this);

    /** Удар маньяка. Тримається полем, бо хук AttackEntityEvent кличе його напряму. */
    private final ManiacCombatModule combat = new ManiacCombatModule(() -> this);

    /**
     * Хп, стаміна, падіння, підняття непритомних, HUD-показники.
     * Тримається полем (а не лише зареєстрована через registerModule),
     * бо LivingFallEvent-хук у ServerHooks і мережеві обробники
     * (onStandUpAttempt, onRescueHold) звертаються до нього напряму —
     * той самий патерн, що generators/combat вище.
     */
    private final com.log_to_kot.maniacmod.survivors.SurvivorModule survivors =
        new com.log_to_kot.maniacmod.survivors.SurvivorModule(() -> this);

    /**
     * Слоти інвентаря обох ролей і лобі. Координаційна річ між
     * survivors/ і maniacs/ (обидва мають свою кількість слотів), тому
     * живе тут поруч з PhaseNetworkSync/MatchStartCoordinator, а не в
     * одному з предметних пакетів.
     */
    private final InventoryAllocationModule inventoryAllocation = new InventoryAllocationModule(() -> this);

    public MatchOrchestrator() {
        Phases.bind(phases);
        // Синхронізація фази з клієнтами — теж звичайний модуль, а не
        // особливий випадок усередині PhaseManager. Реєструється
        // першим, щоб клієнт дізнався про фазу раніше, ніж модулі
        // почнуть слати свої пакети для цієї фази.
        phases.register(new PhaseNetworkSync());
        phases.register(new MatchStartCoordinator());
        phases.register(inventoryAllocation);
        phases.register(generators);
        phases.register(combat);
        phases.register(survivors);
    }

    /** Модуль генераторів — для GeneratorBlock і ServerPacketHandler. */
    public GeneratorModule generatorModule() {
        return generators;
    }

    /** Модуль удару — для хука AttackEntityEvent. */
    public ManiacCombatModule combat() {
        return combat;
    }

    /** Модуль хп/стаміни/падіння/підняття — для ServerHooks і ServerPacketHandler. */
    public com.log_to_kot.maniacmod.survivors.SurvivorModule survivors() {
        return survivors;
    }

    /** Модуль слотів інвентаря — для ServerHooks (реконект). */
    public InventoryAllocationModule inventoryAllocation() {
        return inventoryAllocation;
    }

    /** Модуль, що розсилає фазу клієнтам. Нічого більше не робить. */
    private static final class PhaseNetworkSync implements PhaseListener {
        @Override public String id() { return "phase-sync"; }

        @Override
        public void onPhaseEnter(GamePhase phase, java.util.List<ServerPlayer> players) {
            com.log_to_kot.maniacmod.server.ServerHooks.broadcastPhase(players, phase);
        }

    }

    /** Узгоджує підготовку плану і фізичне застосування розкидання. */
    private final class MatchStartCoordinator implements PhaseListener {
        @Override public String id() { return "match-start"; }

        @Override
        public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
            if (phase == GamePhase.CINEMATIC) {
                prepareSpawnPlan();
            } else if (phase == GamePhase.SCATTER) {
                scatterApplied = applySpawnPlan(players);
                scatterFailed = !scatterApplied;
            }
        }
    }

    // ── Лобі (shaurma-lib LobbyModule читає це) ──────────────────────────

    /** Поточна точка лобі — передається у withLobby(...) як постачальник. */
    public LobbySpawnPoint currentLobbySpawn() {
        return lobbySpawn;
    }

    public void setLobbySpawn(double x, double y, double z, float yaw) {
        this.lobbySpawn = new LobbySpawnPoint(x, y, z, yaw);
    }

    /** Loads persistent map markup into the lobby context. */
    public void reloadConfiguredMap() {
        MapPointConfigs.Snapshot configured = MapPointConfigs.snapshot();
        setLobbySpawn(configured.lobbyX(), configured.lobbyY(),
            configured.lobbyZ(), configured.lobbyYaw());
        if (!phases.is(GamePhase.LOBBY)) return;

        context.spawnPoints().clear();
        context.map().clear();
        addConfiguredPoints(SpawnPointKind.SURVIVOR, configured.survivorSpawns());
        addConfiguredPoints(SpawnPointKind.MANIAC, configured.maniacSpawns());
        addConfiguredPoints(SpawnPointKind.ITEM, configured.itemPoints());
        addConfiguredPoints(SpawnPointKind.GENERATOR, configured.generatorPoints());
        addConfiguredPoints(SpawnPointKind.EXIT, configured.exitPoints());
    }

    private void addConfiguredPoints(SpawnPointKind kind,
                                     List<MapPointConfigs.PointData> points) {
        for (MapPointConfigs.PointData point : points) {
            context.addSpawnPoint(new com.log_to_kot.maniacmod.spawn.SpawnPoint(
                point.pos(), point.yaw(), kind, point.ownerId()));
        }
    }

    // ── Доступ ───────────────────────────────────────────────────────────

    public PhaseManager phases()      { return phases; }
    public SpawnPlanner spawnPlanner() { return spawnPlanner; }

    // ── Фасад над MatchContext ───────────────────────────────────────────
    //
    // MatchContext сам по собі package-private (див. коментар у файлі
    // класу): жодного "дай мені весь контекст" ззовні пакету core.match.
    // Кожен метод нижче — це одна конкретна дія, яку модуль/пакет/пакет
    // мережі реально виконує. Немає методу під конкретну потребу — це
    // сигнал ДОДАТИ його тут, а не обходити фасад.
    //
    // Правило для нових методів: назва каже, ЩО робиться ("healSurvivor",
    // "assignedSpawnPoints"), а не "дай мені шматок стану, я розберусь".

    /** Чи цей гравець зараз маньяк цього матчу. */
    public boolean isManiac(UUID playerId) {
        return context.isManiac(playerId);
    }

    /** Чи цей гравець зараз виживий цього матчу. */
    public boolean isSurvivor(UUID playerId) {
        return context.isSurvivor(playerId);
    }

    /** Архетип поточного маньяка. null, якщо ще не обраний (фаза MENU). */
    public ManiacArchetype maniacArchetype() {
        return context.maniacArchetype();
    }

    /** Роль виживого за id. null, якщо гравець не виживий цього матчу. */
    public com.log_to_kot.maniacmod.survivors.SurvivorRole survivorRoleOf(UUID playerId) {
        return context.roleOf(playerId);
    }

    /** Поточний стан виживого (HEALTHY/BROKEN_LEG/CRAWLING/UNCONSCIOUS). */
    public com.log_to_kot.maniacmod.survivors.SurvivorState survivorStateOf(UUID playerId) {
        return context.stateOf(playerId);
    }

    /**
     * Змінює стан виживого. Викликає лише {@code SurvivorModule} —
     * інші модулі не мають підстав напряму переставляти машину станів
     * гравця (падіння/нога/непритомність — усе рахує один модуль).
     */
    public void setSurvivorState(UUID playerId, com.log_to_kot.maniacmod.survivors.SurvivorState state) {
        context.setSurvivorState(playerId, state);
    }

    /** Поточне хп. -1, якщо гравець не виживий цього матчу. */
    public int hpOf(UUID playerId) {
        return context.hpOf(playerId);
    }

    /** Максимум хп за роллю. -1, якщо гравець не виживий. */
    public int maxHpOf(UUID playerId) {
        return context.maxHpOf(playerId);
    }

    /**
     * Лікує виживого. Повертає, скільки хп реально відновлено — 0,
     * якщо гравець не виживий або вже мав повне хп. Викликач (предмет)
     * саме за цим числом вирішує, чи витрачати себе.
     */
    public int healSurvivor(UUID playerId, int amount) {
        return context.heal(playerId, amount);
    }

    /** Знімає хп. true, якщо гравець щойно дійшов до 0. */
    public boolean damageSurvivor(UUID playerId, int amount) {
        return context.damage(playerId, amount);
    }

    /** Скільки виживих ще в матчі (не рахує втеклих і вибулих). */
    public int aliveSurvivorCount() {
        return context.aliveSurvivorCount();
    }

    /** UUID усіх виживих цього матчу — лише для читання. */
    public List<UUID> survivorIds() {
        return context.survivorIds();
    }

    /** Хто вже втік цього матчу. */
    public List<UUID> escapedSurvivorIds() {
        return context.escapedIds();
    }

    /** Досягнення матчу (POWER_RESTORED, EXIT_OPENED...) — лише читання. */
    public java.util.Set<MatchObjectives.Objective> completedObjectives() {
        return context.objectives().completed();
    }

    /** Позначає ціль матчу досягнутою. Викликають лише зони/генератори. */
    public void completeObjective(MatchObjectives.Objective objective) {
        context.objectives().complete(objective);
    }

    /** Генератор за позицією. null, якщо на цій позиції генератора немає. */
    public com.log_to_kot.maniacmod.map.zones.GeneratorPoi generatorAt(
            net.minecraft.core.BlockPos pos) {
        for (var g : generators()) {
            if (g.pos().equals(pos)) return g;
        }
        return null;
    }

    /** Усі генератори карти — лише для читання (статус, підрахунок). */
    public List<com.log_to_kot.maniacmod.map.zones.GeneratorPoi> generators() {
        return context.map().generators();
    }

    /** Розмітка точок цього матчу — лише для читання (команди, план спавну). */
    public List<com.log_to_kot.maniacmod.spawn.SpawnPoint> spawnPoints() {
        return context.spawnPoints();
    }

    /** Додає точку розмітки (команда /maniac point add). */
    public void addSpawnPoint(com.log_to_kot.maniacmod.spawn.SpawnPoint point) {
        context.addSpawnPoint(point);
    }

    /** Стирає всю розмітку карти (команда /maniac point clear). */
    public void clearMap() {
        context.spawnPoints().clear();
        context.map().clear();
    }

    /** Рядок статусу для /maniac status — єдине місце, що читає кілька полів разом. */
    public String debugStatusLine() {
        return "виживих: " + context.aliveSurvivorCount()
            + " | втекло: " + context.escapedIds().size()
            + " | точок: " + context.spawnPoints().size()
            + " | досягнення: " + context.objectives().completed();
    }

    /** Модулі реєструють свої PhaseListener тут, зазвичай на старті сервера. */
    public void registerModule(PhaseListener listener) {
        phases.register(listener);
    }

    /** Сервер потрібен для аварійного очищення при RESET і shutdown. */
    public void attachServer(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Гравець за UUID серед реально онлайн зараз — null, якщо вийшов
     * або сервер ще не прикріплений. Для модулів, яким потрібен
     * ServerPlayer поза тіковим списком players (наприклад rescue-сесія,
     * що завершується не в той самий тік, коли почалась).
     */
    public ServerPlayer onlinePlayer(UUID id) {
        return server == null ? null : server.getPlayerList().getPlayer(id);
    }

    /** Реєструє будь-яку тимчасову сутність, створену ігровим модулем. */
    public void registerRuntimeEntity(Entity entity) {
        MatchRuntimeRegistry.register(entity);
    }

    /** Реєструє блок, який матч тимчасово змінює або ставить на карту. */
    public void registerRuntimeBlock(ServerLevel level, BlockPos position) {
        MatchBlockRegistry.register(level, position);
    }

    /**
     * Ставить тимчасовий блок матчу без ризику забути його очищення.
     * Оригінальний блок (зокрема AIR) зберігається до зміни карти.
     */
    public void placeRuntimeBlock(ServerLevel level, BlockPos position,
                                  BlockState state) {
        MatchBlockRegistry.register(level, position);
        level.setBlock(position, state, 3);
    }

    /**
     * Міст до shaurma-lib. v3-еквівалент: ManiacGameManager.attachLifecycle().
     * Тепер бібліотека отримує стан із фаз, а не з окремого enum GameState —
     * тобто дзеркалення живе в одному місці й не може розійтися з реальністю.
     */
    public void attachLifecycle(PhaseManager.LifecycleSink sink) {
        phases.attachLifecycleSink(sink);
    }

    // ── Запуск матчу ─────────────────────────────────────────────────────

    /**
     * Старт. Створює НОВИЙ MatchContext — тому жодного ручного
     * очищення двадцяти списків, як було в resetGame().
     *
     * CINEMATIC і планування — дві незалежні гілки старту. Кінематика
     * відраховує власний час, а план готовності перевіряється окремо;
     * SCATTER починається лише коли готові обидві.
     */
    public boolean start(List<ServerPlayer> players, ServerPlayer maniacPlayer,
                         ManiacArchetype maniacArchetype) {
        if (!phases.is(GamePhase.LOBBY)) return false;
        if (players.size() < ManiacConfigs.get(ConfigSchema.MIN_PLAYERS)) return false;

        List<com.log_to_kot.maniacmod.spawn.SpawnPoint> configuredPoints =
            List.copyOf(context.spawnPoints());
        releaseSpawnPoints(configuredPoints);

        MatchContext fresh = new MatchContext();
        fresh.assignManiac(maniacPlayer.getUUID(), maniacArchetype);
        for (var point : configuredPoints) fresh.addSpawnPoint(point);

        List<UUID> survivorIds = new ArrayList<>();
        for (ServerPlayer p : players) {
            if (p.getUUID().equals(maniacPlayer.getUUID())) continue;
            fresh.addSurvivor(p.getUUID(), SurvivorRegistry.defaultRole());
            survivorIds.add(p.getUUID());
        }

        // TODO(міграція): підвантажити розмітку точок світу у fresh
        //                 перед плануванням (див. spawn/README.md).

        // Перевірка розмітки ДО зміни фази: якщо точок менше, ніж гравців,
        // краще сказати це в лобі, ніж посеред розкидання.
        int survivorPoints = SpawnPlanner
            .countByKind(fresh.spawnPoints())
            .getOrDefault(SpawnPointKind.SURVIVOR, 0);
        if (!fresh.spawnPoints().isEmpty() && survivorPoints < survivorIds.size()) {
            return false;
        }

        MatchContext previous = this.context;
        this.context = fresh;
        pendingSpawnPlan = null;
        plannerReady = false;
        scatterApplied = false;
        scatterFailed = false;

        try {
            advanceTo(GamePhase.CINEMATIC, players);
        } catch (SpawnPlanner.SpawnPlanFailure failure) {
            this.context = previous;
            pendingSpawnPlan = null;
            plannerReady = false;
            scatterApplied = false;
            scatterFailed = false;
            advanceTo(GamePhase.LOBBY, players);
            return false;
        }
        return true;
    }

    // ── Переходи ─────────────────────────────────────────────────────────

    public void advanceTo(GamePhase next, List<ServerPlayer> players) {
        phases.transitionTo(next, players);
    }

    /**
     * Один серверний тік. ServerEventHandler викликає ЦЕ і більше
     * нічого — без власних перевірок фази.
     *
     * v3-еквівалент: у ServerEventHandler.onServerTick() стояв рядок
     *   if (getGameState() != RUNNING) return;
     * а далі шість ручних викликів tickBearTraps/tickWires/tickRope/...
     * Кожна нова механіка означала ще один рядок саме там. Тепер
     * модуль сам вирішує, чи йому тікати, через свій PhaseListener.
     */
    public void tick(List<ServerPlayer> players) {
        context.tick();
        phases.tick(players);
        checkPhaseExitConditions(players);
    }

    /**
     * Єдине місце, де вирішується "чи пора далі".
     *
     * ── Переходи йдуть від ДІЙ, а не від кількості гравців ───────────
     * HUNT → POWERED: подано живлення (генератори доведені до кінця).
     * POWERED → FINALE: відкрито вихід.
     * Будь-яка ігрова фаза → ENDING: виживих не лишилось узагалі.
     *
     * Кількість гравців ніде не є умовою ПРОГРЕСУ. «Лишився один
     * виживий» — не досягнення команди: решта могла вийти з гри або
     * впасти з даху, і фінал у такому матчі нічого не означає.
     * Єдине місце, де рахуються люди, — умова завершення, і це вже
     * не прогрес.
     */
    private void checkPhaseExitConditions(List<ServerPlayer> players) {
        if (phases.is(GamePhase.CINEMATIC)) {
            if (!plannerReady) {
                try {
                    prepareSpawnPlan();
                } catch (SpawnPlanner.SpawnPlanFailure failure) {
                    reset(players);
                    return;
                }
            }
            if (plannerReady
                && phases.ticksInPhase() >= cinematicTicks()) {
                advanceTo(GamePhase.SCATTER, players);
            }
            return;
        }

        if (phases.is(GamePhase.SCATTER)) {
            if (scatterFailed) {
                reset(players);
                return;
            }
            if (scatterApplied) advanceTo(GamePhase.ROLE_REVEAL, players);
            return;
        }

        if (phases.is(GamePhase.ROLE_REVEAL)) {
            if (phases.ticksInPhase() >= roleRevealTicks()) {
                advanceTo(GamePhase.HUNT, players);
            }
            return;
        }

        if (phases.is(GamePhase.ENDING)) {
            if (phases.ticksInPhase() >= endingTicks()) reset(players);
            return;
        }

        if (!phases.isGameplay()) return;

        if (noSurvivorsLeft()) {
            advanceTo(GamePhase.ENDING, players);
            return;
        }

        MatchObjectives objectives = context.objectives();

        switch (phases.current()) {
            case HUNT -> {
                if (allRequiredGeneratorsDone()) {
                    objectives.complete(MatchObjectives.Objective.POWER_RESTORED);
                    advanceTo(GamePhase.POWERED, players);
                }
            }
            case POWERED -> {
                if (objectives.isComplete(MatchObjectives.Objective.EXIT_OPENED)) {
                    advanceTo(GamePhase.FINALE, players);
                }
            }
            default -> { /* FINALE завершується лише через noSurvivorsLeft */ }
        }
    }

    /**
     * Готово стільки генераторів, скільки вимагає конфіг. Не «усі, що
     * є на карті»: генераторів може бути розмічено більше, ніж треба —
     * це нормальний дизайн, коли команда обирає, які саме лагодити.
     */
    private boolean allRequiredGeneratorsDone() {
        int required = ManiacConfigs.get(ConfigSchema.GENERATORS_REQUIRED);
        long done = generators().stream()
            .filter(g -> g.isCompleted())
            .count();
        return done >= required;
    }

    private boolean noSurvivorsLeft() {
        return context.aliveSurvivorCount() == 0;
    }

    private int cinematicTicks() {
        return ManiacConfigs.get(ConfigSchema.CINEMATIC_SECONDS) * 20;
    }

    private int roleRevealTicks() {
        return ManiacConfigs.get(ConfigSchema.ROLE_REVEAL_SECONDS) * 20;
    }

    private int endingTicks() {
        return ManiacConfigs.get(ConfigSchema.ENDING_SECONDS) * 20;
    }

    /**
     * Планувальник поки що синхронний навмисно. Контракт уже відокремлений
     * від таймера кінематики, тому пізніше виконання можна винести в
     * executor без зміни правил переходу фаз.
     */
    private void prepareSpawnPlan() {
        if (plannerReady) return;

        // numManiacs = 1: зараз матч підтримує рівно одного маньяка
        // (context.maniacUUID() — скаляр). Коли з'явиться підтримка
        // двох маньяків одночасно, тут достатньо підставити реальну
        // кількість — формула бонусних генераторів у SpawnPlanner уже
        // готова, змінювати саму логіку розкидання не треба.
        pendingSpawnPlan = spawnPlanner.plan(
            context.spawnPoints(),
            context.maniacArchetype().id(),
            context.survivorIds(),
            1);
        plannerReady = true;
    }

    private boolean applySpawnPlan(List<ServerPlayer> players) {
        if (pendingSpawnPlan == null) return false;

        ServerPlayer maniac = findPlayer(players, context.maniacUUID());
        if (maniac == null) return false;
        for (UUID survivorId : pendingSpawnPlan.survivorPoints().keySet()) {
            if (findPlayer(players, survivorId) == null) return false;
        }

        teleportToSpawn(maniac, pendingSpawnPlan.maniacPoint());
        context.rememberSpawn(maniac.getUUID(), pendingSpawnPlan.maniacPoint());

        for (var entry : pendingSpawnPlan.survivorPoints().entrySet()) {
            ServerPlayer survivor = findPlayer(players, entry.getKey());
            teleportToSpawn(survivor, entry.getValue());
            context.rememberSpawn(entry.getKey(), entry.getValue());
        }

        for (var point : pendingSpawnPlan.engagedItemPoints()) point.engage();
        context.map().clear();
        for (var point : pendingSpawnPlan.generatorPoints()) {
            context.map().addGenerator(new GeneratorPoi(point.pos()));
        }
        return true;
    }

    private static ServerPlayer findPlayer(List<ServerPlayer> players, UUID id) {
        for (ServerPlayer player : players) {
            if (player.getUUID().equals(id)) return player;
        }
        return null;
    }

    private static void teleportToSpawn(ServerPlayer player,
                                        com.log_to_kot.maniacmod.spawn.SpawnPoint point) {
        player.teleportTo(
            player.serverLevel(),
            point.pos().getX() + 0.5,
            point.pos().getY(),
            point.pos().getZ() + 0.5,
            point.yaw(),
            player.getXRot());
    }

    private static void releaseSpawnPoints(List<com.log_to_kot.maniacmod.spawn.SpawnPoint> points) {
        for (var point : points) point.release();
    }

    // ── Завершення ───────────────────────────────────────────────────────

    /**
     * Гравець вийшов. Маньяк, що вийшов, завершує матч — інакше
     * виживі бігали б порожньою картою (у v3 матч просто зависав).
     */
    public void onPlayerLeft(ServerPlayer player) {
        List<ServerPlayer> online = player.getServer().getPlayerList().getPlayers();
        if (context.isManiac(player.getUUID())) {
            if (!phases.is(GamePhase.LOBBY) && !phases.is(GamePhase.RESET)) reset(online);
            return;
        }

        if (phases.is(GamePhase.LOBBY) || phases.is(GamePhase.RESET)
            || phases.is(GamePhase.ENDING)) return;

        context.removeSurvivor(player.getUUID());
        pendingSpawnPlan = null;
        plannerReady = false;
        scatterApplied = false;
        scatterFailed = phases.is(GamePhase.SCATTER);

        if (context.aliveSurvivorCount() == 0) {
            advanceTo(GamePhase.ENDING, online);
        }
    }

    /**
     * Маньяк обрав персонажа в меню. Викликається з
     * ServerPacketHandler після перевірки id у реєстрі.
     */
    public void onManiacChosen(ServerPlayer player, ManiacArchetype archetype) {
        if (!phases.is(GamePhase.ROLE_REVEAL) && !phases.is(GamePhase.LOBBY)) return;
        if (!context.isManiac(player.getUUID())) return;

        context.assignManiac(player.getUUID(), archetype);
    }

    public void reset(List<ServerPlayer> players) {
        if (server != null) MatchRuntimeRegistry.cleanup(server);
        if (server != null) MatchBlockRegistry.restoreAll(server);
        releaseSpawnPoints(context.spawnPoints());
        pendingSpawnPlan = null;
        plannerReady = false;
        scatterApplied = false;
        scatterFailed = false;
        advanceTo(GamePhase.RESET, players);
        this.context = new MatchContext();
        advanceTo(GamePhase.LOBBY, players);
    }

    /** Зупинка сервера — знімаємо статичне посилання, щоб не текло. */
    public void shutdown() {
        if (server != null) MatchRuntimeRegistry.cleanup(server);
        if (server != null) MatchBlockRegistry.restoreAll(server);
        server = null;
        Phases.unbind();
    }
}
