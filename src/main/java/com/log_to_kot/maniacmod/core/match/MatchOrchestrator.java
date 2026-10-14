package com.log_to_kot.maniacmod.core.match;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.config.MapPointConfigs;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.core.phase.PhaseManager;
import com.log_to_kot.maniacmod.core.phase.Phases;
import com.log_to_kot.maniacmod.loot.LootModule;
import com.log_to_kot.maniacmod.map.GeneratorModule;
import com.log_to_kot.maniacmod.map.zones.GeneratorPoi;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacCombatModule;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.spawn.SpawnPlanner;
import com.log_to_kot.maniacmod.spawn.SpawnPointKind;
import com.log_to_kot.maniacmod.survivors.SurvivorRegistry;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import com.log_to_kot.maniacmod.world.WorldEnvironmentModule;
import dev.shaurmalib.common.lobby.LobbySpawnPoint;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
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

    /**
     * UUID → ім'я гравців, що вийшли посеред матчу й лишили тіло. Ім'я потрібне,
     * щоб ростер (таб) і підсумок показували офлайн-учасника, коли його вже немає
     * у списку онлайн-гравців. Очищається разом із матчем.
     */
    private final java.util.Map<UUID, String> offlineNames = new java.util.HashMap<>();

    /**
     * Тіло маньяка знищено (порожнеча, {@code /kill}: усе, що обходить захист). Скидати
     * матч прямо зі слухача lib небезпечно (ми всередині його retire), тому лише
     * піднімаємо прапор: скидання робить наступний тік у {@link #checkPhaseExitConditions}.
     */
    private boolean maniacBodyLost;
    private MinecraftServer server;
    private SpawnPlanner.SpawnPlan pendingSpawnPlan;
    /**
     * Чому останній {@link #start} не вдався, якщо причина — розмітка
     * карти; інакше null. Потрібно, щоб команда старту казала адміну
     * КОНКРЕТНО «потрібно 6 точок генераторів, а є 3», а не загальне
     * «перевір розмітку» (див. ManiacCommand). Скидається на початку
     * кожного start(), щоб стара причина не липла до нового невдалого.
     */
    private String lastStartFailure;
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
     * Пастки маньяка: розміщення, спрацювання, звільнення. Тримається
     * полем, бо сутність капкана, хук лома й мережеві обробники
     * звертаються до нього напряму — той самий патерн, що generators/combat.
     */
    private final com.log_to_kot.maniacmod.maniacs.ManiacSpeedModule maniacSpeed =
        new com.log_to_kot.maniacmod.maniacs.ManiacSpeedModule(() -> this);

    private final com.log_to_kot.maniacmod.traps.TrapModule traps =
        new com.log_to_kot.maniacmod.traps.TrapModule(() -> this);

    /**
     * Стан «оглушений маньяк» від удару ломом ПО МАНЬЯКУ. Тримається
     * полем, бо хук AttackEntityEvent (лом по маньяку) і
     * ServerPacketHandler (перевірка «чи можна активувати здібність/
     * поставити пастку зараз») звертаються до нього напряму — той
     * самий патерн, що combat/traps вище.
     */
    private final com.log_to_kot.maniacmod.maniacs.ManiacStunModule maniacStun =
        new com.log_to_kot.maniacmod.maniacs.ManiacStunModule(() -> this);

    /**
     * Розкладає предмети по ITEM-точках. Не PhaseListener: спавн луту —
     * частина {@link #applySpawnPlan} (як і спавн генераторів), у нього
     * немає власного життєвого циклу. Прибирання лежачих предметів
     * робить {@code MatchRuntimeRegistry.cleanup} у reset()/shutdown().
     */
    private final LootModule loot = new LootModule(rng);

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
    private final WorldEnvironmentModule worldEnvironment = new WorldEnvironmentModule(() -> this);
    private final com.log_to_kot.maniacmod.intro.IntroModule intro =
        new com.log_to_kot.maniacmod.intro.IntroModule(() -> this);

    /**
     * Евакуація (приймач → ракета → вертоліт → мотузка). Тримається полем, бо сутності
     * приймача/мотузки й хуки SurvivorModule звертаються до нього напряму — той самий
     * патерн, що generators/combat.
     */
    private final com.log_to_kot.maniacmod.evac.EvacModule evac =
        new com.log_to_kot.maniacmod.evac.EvacModule(() -> this);

    /**
     * Гаки: лежить → на плечі → на гаку. Тримається полем, бо сутності гака/сидіння, хук ПКМ
     * (ServerHooks), SurvivorModule і ManiacCombatModule звертаються до нього напряму.
     */
    private final com.log_to_kot.maniacmod.hooks.HookModule hooks =
        new com.log_to_kot.maniacmod.hooks.HookModule(() -> this);

    /**
     * Сцена перемоги маніяка (VICTORY_SCENE). Тримається полем з тієї ж причини, що intro/evac/hooks:
     * оркестратор питає її «чи можна грати» і «чи скінчилась», а сутності гачків — «чи ти моя».
     */
    private final com.log_to_kot.maniacmod.victory.VictorySceneModule victory =
        new com.log_to_kot.maniacmod.victory.VictorySceneModule(() -> this);

    /** Freecam екрана підсумків (STATS_CAMERA): камера гравців на фазі ENDING. */
    private final com.log_to_kot.maniacmod.victory.StatsCameraModule statsCamera =
        new com.log_to_kot.maniacmod.victory.StatsCameraModule(() -> this);

    /** Freecam статистики: сервер знімає її, коли гравець закрив екран підсумків. */
    public com.log_to_kot.maniacmod.victory.StatsCameraModule statsCamera() {
        return statsCamera;
    }

    /**
     * Історія матчів: архів, списки, відкриття одного матчу. Тримається полем, бо мережеві обробники
     * ({@code HistoryListRequestPacket}, {@code HistoryDetailRequestPacket}) звертаються до нього напряму —
     * той самий патерн, що generators/hooks. ПИШЕ в нього лише оркестратор ({@link #archiveMatch}).
     */
    private final com.log_to_kot.maniacmod.history.MatchHistoryService history =
        new com.log_to_kot.maniacmod.history.MatchHistoryService();

    public com.log_to_kot.maniacmod.history.MatchHistoryService history() {
        return history;
    }

    public MatchOrchestrator() {
        Phases.bind(phases);
        // Синхронізація фази з клієнтами — теж звичайний модуль, а не
        // особливий випадок усередині PhaseManager. Реєструється
        // першим, щоб клієнт дізнався про фазу раніше, ніж модулі
        // почнуть слати свої пакети для цієї фази.
        phases.register(new PhaseNetworkSync());
        phases.register(new MatchStartCoordinator());
        // Кіно-вступ: після координатора (той готує план у CINEMATIC), до решти модулів.
        phases.register(intro);
        phases.register(inventoryAllocation);
        phases.register(generators);
        phases.register(evac);
        phases.register(combat);
        phases.register(traps);
        phases.register(maniacStun);
        phases.register(maniacSpeed);
        phases.register(hooks);
        phases.register(barricades);
        phases.register(victory);
        phases.register(statsCamera);
        phases.register(survivors);
        phases.register(worldEnvironment);
    }

    /**
     * Барикади (закриті зони). Тримається полем: хуки ПКМ ({@code BarricadeHooks}), команди й
     * сутності звертаються до нього напряму — той самий патерн, що evac/hooks.
     */
    private final com.log_to_kot.maniacmod.barricade.BarricadeModule barricades =
        new com.log_to_kot.maniacmod.barricade.BarricadeModule(() -> this);

    /** Барикади — для BarricadeHooks і /maniac barricade. */
    public com.log_to_kot.maniacmod.barricade.BarricadeModule barricades() {
        return barricades;
    }

    /** Сцена перемоги маніяка — для сутностей і команди прев'ю. */
    public com.log_to_kot.maniacmod.victory.VictorySceneModule victory() {
        return victory;
    }

    /** Кіно-вступ — для команди прев'ю Replay. */
    public com.log_to_kot.maniacmod.intro.IntroModule intro() {
        return intro;
    }

    /** Модуль генераторів — для GeneratorEntity і ServerPacketHandler. */
    public com.log_to_kot.maniacmod.evac.EvacModule evac() {
        return evac;
    }

    /** Гаки — для HookEntity/VictimSeatEntity, ServerHooks, SurvivorModule, команд. */
    public com.log_to_kot.maniacmod.hooks.HookModule hooks() {
        return hooks;
    }

    public GeneratorModule generatorModule() {
        return generators;
    }

    /** Модуль удару — для хука AttackEntityEvent. */
    public ManiacCombatModule combat() {
        return combat;
    }

    /** Стан «оглушений маньяк» від удару ломом — для хука AttackEntityEvent і ServerPacketHandler. */
    public com.log_to_kot.maniacmod.maniacs.ManiacStunModule maniacStun() {
        return maniacStun;
    }

    /** Модуль швидкості маньяка — для дебаг-зняття ролі поза лобі (debugUnmorph). */
    public com.log_to_kot.maniacmod.maniacs.ManiacSpeedModule maniacSpeed() {
        return maniacSpeed;
    }

    /** Модуль пасток — для BearTrapEntity, хука лома й ServerPacketHandler. */
    public com.log_to_kot.maniacmod.traps.TrapModule traps() {
        return traps;
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
    private final class PhaseNetworkSync implements PhaseListener {
        @Override public String id() { return "phase-sync"; }

        @Override
        public void onPhaseEnter(GamePhase phase, java.util.List<ServerPlayer> players) {
            // Хронологія для історії: кожна зміна фази — подія і межа відрізка.
            context.timeline().onPhase(context.matchTick(), phase);
            // ПОРЯДОК ВАЖЛИВИЙ: роль йде РАНІШЕ за фазу.
            //
            // ClientMatchState.setPhase(LOBBY/RESET) викликає reset(), який
            // скидає роль у SPECTATOR. Якщо слати фазу першою, а роль
            // другою, для переходу в LOBBY порядок безпечний (роль потім
            // теж буде SPECTATOR), але для ROLE_REVEAL/HUNT — навпаки:
            // роль мусить бути на клієнті ДО того, як HUD вперше спитає
            // "я виживий?" у ту ж мить, коли прийшла нова фаза.
            //
            // БАГ (шкала ХП/стаміни зникала після старту гри): RoleSyncPacket
            // не надсилався ніде, крім входу на сервер (ServerHooks) і
            // дебаг-morph (MatchOrchestrator.morph*). start() створює
            // НОВИЙ MatchContext із новими ролями, але клієнт про це не
            // дізнавався — лишався SPECTATOR, і SurvivorVitalsOverlay
            // одразу виходив на isSurvivor(). У лобі шкала працювала лише
            // тому, що /maniac morph survivor шле роль вручну.
            //
            // Ролі змінюються лише при старті матчу (context = fresh) і
            // при скиданні — обидва випадки проходять через перехід фази,
            // тож ОДНОГО місця тут достатньо; окремої розсилки в start()
            // не потрібно (два джерела правди — саме те, чого забороняє
            // AI_CODE_GUIDE).
            for (ServerPlayer player : players) {
                com.log_to_kot.maniacmod.net.ModNetwork.toPlayer(player, roleSyncFor(player));
            }
            // Результат матчу — РАНІШЕ за фазу ENDING: екран підсумків відкривається саме на
            // ній і мусить уже мати, кого показувати переможцем.
            if (phase == GamePhase.ENDING) {
                var summary = matchSummary();
                for (ServerPlayer player : players) {
                    com.log_to_kot.maniacmod.net.ModNetwork.toPlayer(player, summary);
                }
                // Особисті нагороди XP — одразу після підсумку, до розсилки фази.
                var rewards = com.log_to_kot.maniacmod.progression.MatchRewardService.awardAll(summary, players);
                // Історія: той самий знімок, що бачить екран підсумків, + хто за що отримав XP.
                archiveMatch(summary, rewards, resolveEnd());
                var outcome = new com.log_to_kot.maniacmod.net.s2c.matchstate.MatchOutcomePacket(
                    survivorsWon(), escapedSurvivorIds().size(), survivorTotal());
                for (ServerPlayer player : players) {
                    com.log_to_kot.maniacmod.net.ModNetwork.toPlayer(player, outcome);
                }
                ManiacMod.LOGGER.info("[match] підсумок: {} (втекло {}/{})",
                    survivorsWon() ? "ПЕРЕМОГА ВИЖИВИХ" : "ПЕРЕМОГА МАНЬЯКА",
                    escapedSurvivorIds().size(), survivorTotal());
            }
            com.log_to_kot.maniacmod.server.ServerHooks.broadcastPhase(players, phase);
            // Ролі й стани щойно могли змінитись цілком (ROLE_REVEAL,
            // RESET) — таб має побачити новий склад одразу, не чекаючи
            // наступного throttled roster-тіку з SurvivorModule.
            com.log_to_kot.maniacmod.server.ServerHooks.broadcastRoster(players);
        }

    }

    /**
     * Роль конкретного гравця для {@link RoleSyncPacket}. Єдине місце, де
     * рішення "хто я" перетворюється на пакет — раніше ця логіка жила
     * приватним статичним методом у {@code ServerHooks} (дубль правди
     * поруч із самим MatchContext, до якого ServerHooks не має доступу).
     */
    public RoleSyncPacket roleSyncFor(ServerPlayer player) {
        UUID id = player.getUUID();
        if (isManiac(id)) {
            ManiacArchetype archetype = maniacArchetype();
            return new RoleSyncPacket(RoleSyncPacket.Role.MANIAC,
                archetype == null ? "" : archetype.id(),
                archetype == null ? 0.0 : archetype.attackRangeBlocks());
        }
        if (isSurvivor(id)) {
            var role = survivorRoleOf(id);
            return new RoleSyncPacket(RoleSyncPacket.Role.SURVIVOR,
                role == null ? "" : role.id(), 0.0);
        }
        return new RoleSyncPacket(RoleSyncPacket.Role.SPECTATOR, "", 0.0);
    }

    /** Узгоджує підготовку плану і фізичне застосування розкидання. */
    private final class MatchStartCoordinator implements PhaseListener {
        @Override public String id() { return "match-start"; }

        @Override
        public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
            if (phase == GamePhase.CINEMATIC) {
                prepareSpawnPlan(players);
            } else if (phase == GamePhase.SCATTER) {
                // Новий матч уже йде: «повернутих у лобі» минулого матчу більше нема.
                returnedToLobby.clear();
                scatterApplied = applySpawnPlan(players);
                scatterFailed = !scatterApplied;
            } else if (phase == GamePhase.LOBBY) {
                // БАГ (гравці отримували урон у лобі): вхід у LOBBY тут
                // не робив НІЧОГО з гравцями, що вже були онлайн — тільки
                // ServerHooks.onPlayerJoin викликав lib.lobbyModule()
                // .sendToLobby(...), а це подія ВХОДУ на сервер, не подія
                // "матч завершився/скинутий". Тому гравець, що пережив
                // матч (стоп командою, victory/loss, чи просто був онлайн
                // при /maniac stop чи /stopgame), лишався там, де застала
                // фаза LOBBY: у ADVENTURE/SURVIVAL ігрового світу, без
                // телепорту в лобі-точку і без скидання hp/інвентаря.
                // DamageInterceptorRegistry сам по собі блокує лише
                // ВАНІЛЬНИЙ шлях урону (LivingHurtEvent) — падіння з
                // висоти, вогонь, потоплення це покриває, але воно НЕ
                // телепортує гравця й не приводить його стан у порядок,
                // тож будь-хто, хто в момент завершення матчу залишався
                // серед мобів/пасток на активній карті гри, і надалі
                // отримував "усе" (мобів, залишки пасток, провалювання
                // у порожнечу карти) — усе те, чого немає у власному
                // vanilla LivingHurtEvent і чого interceptor не бачить.
                // Тепер той самий виклик, що робить ServerHooks.onPlayerJoin
                // для гравця, що заходить під час LOBBY, виконується і
                // тут — для ВСІХ, хто вже онлайн, щойно матч повертається
                // у LOBBY (RESET → LOBBY, а також прямий стоп команди).
                for (ServerPlayer player : players) {
                    // Хто дивиться статистику (freecam) — повернеться в лобі, коли закриє екран.
                    if (statsCamera.isViewing(player.getUUID())) continue;
                    // БАГ (повторний телепорт на спавн): хто вже повернутий у лобі раніше
                    // (кінець сцени перемоги, закритий екран статистики) і встиг походити —
                    // не телепортуємо вдруге, інакше його смикає на точку лобі «з нізвідки».
                    if (returnedToLobby.contains(player.getUUID())) continue;
                    returnToLobby(player);
                }
                returnedToLobby.clear();
            }
        }
    }

    /** Хто вже повернутий у лобі після завершення матчу (щоб не телепортувати вдруге). */
    private final Set<UUID> returnedToLobby = new HashSet<>();

    /**
     * ЄДИНЕ місце, де гравця повертають у лобі наприкінці матчу (сцена перемоги, екран
     * статистики, вхід у LOBBY). Запам'ятовує гравця, тож вхід у {@link GamePhase#LOBBY} не
     * телепортує його повторно, якщо він давно вже там.
     */
    public void returnToLobby(ServerPlayer player) {
        ManiacMod.lib().lobbyModule().sendToLobby(player);
        ManiacMod.lib().lobbyModule().hideNameTag(player);
        returnedToLobby.add(player.getUUID());
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
        addConfiguredPoints(SpawnPointKind.RECEIVER, configured.receiverPoints());
        addConfiguredPoints(SpawnPointKind.HOOK, configured.hookPoints());
        addConfiguredPoints(SpawnPointKind.VICTORY_HOOK, configured.victoryHookPoints());
        addConfiguredPoints(SpawnPointKind.VICTORY_MANIAC, configured.victoryManiacPoints());
        addConfiguredPoints(SpawnPointKind.VICTORY_CAMERA, configured.victoryCameraPoints());
        addConfiguredPoints(SpawnPointKind.VICTORY_PLAYER, configured.victoryPlayerPoints());
        addConfiguredPoints(SpawnPointKind.VICTORY_GHOST, configured.victoryGhostPoints());
        addConfiguredPoints(SpawnPointKind.VICTORY_PLAYERS_CAMERA, configured.victoryPlayersCameraPoints());
        addConfiguredPoints(SpawnPointKind.STATS_CAMERA, configured.statsCameraPoints());
    }

    private void addConfiguredPoints(SpawnPointKind kind,
                                     List<MapPointConfigs.PointData> points) {
        for (MapPointConfigs.PointData point : points) {
            context.addSpawnPoint(new com.log_to_kot.maniacmod.spawn.SpawnPoint(
                point.pos(), point.yaw(), kind, point.ownerId(),
                point.pitch(), point.x(), point.y(), point.z()));
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

    /**
     * Усі гравці, що зараз на сервері (виживі, маньяк, глядачі). Для подій,
     * які мають побачити ВСІ, — наприклад вибух генератора. Порожній
     * список, якщо сервер ще не підключено (до старту або після скидання).
     */
    public List<ServerPlayer> onlinePlayers() {
        return server == null ? List.of() : List.copyOf(server.getPlayerList().getPlayers());
    }

    /** Онлайн-гравець за UUID, або null (вийшов / сервер не підключено). */
    public ServerPlayer onlinePlayer(UUID playerId) {
        return server == null ? null : server.getPlayerList().getPlayer(playerId);
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

    /**
     * Дебаг: виставляє хп виживого напряму у ВІДСОТКАХ (0-100) від
     * maxHp його ролі — для {@code /maniac hp set}. true, якщо
     * результат рівно 0 хп (викликач — ManiacCommand — сам вирішує,
     * чи заводити гравця в UNCONSCIOUS тим самим шляхом, що реальний
     * удар маньяка: {@code survivors().onSurvivorDowned(...)}).
     */
    public boolean setSurvivorHpPercent(UUID playerId, int percent) {
        return context.setHpPercent(playerId, percent);
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

    /**
     * ПРАВИЛО ПЕРЕМОГИ — єдине місце. Втік хоча б один виживий — перемога виживих; не втік
     * ніхто — перемога маньяка. Умова «живих, що не втекли, нуль» (завершення матчу) —
     * окремо, в {@code checkPhaseExitConditions}; тут лише хто виграв, коли матч скінчився
     * (нуль живих, або вертоліт відлетів із тими, хто лишився).
     */
    public boolean survivorsWon() {
        Boolean forced = context.forcedSurvivorsWin();
        if (forced != null) return forced;
        return !context.escapedIds().isEmpty();
    }

    /** Скільки виживих було в матчі: втекли + вибули + ще в грі (для «3/6»). */
    public int survivorTotal() {
        return context.escapedIds().size() + context.eliminatedIds().size() + context.aliveSurvivorCount();
    }

    /** Хто вибув остаточно (непритомний не дочекався підняття) цього матчу. */
    public List<UUID> eliminatedSurvivorIds() {
        return context.eliminatedIds();
    }

    /** Гравець дійшов до зони втечі здоровим — фіксує втечу. */
    public void markEscaped(ServerPlayer player) {
        boolean wasInGame = context.isSurvivor(player.getUUID());
        context.markEscaped(player.getUUID(), player.getGameProfile().getName());
        if (wasInGame) noteEscaped(player.getUUID());
    }

    /** Непритомний помер — гравець вибуває з матчу остаточно. */
    public void markEliminated(ServerPlayer player) {
        boolean wasInGame = context.isSurvivor(player.getUUID());
        context.markEliminated(player.getUUID(), player.getGameProfile().getName());
        if (wasInGame) noteEliminated(player.getUUID());
    }

    // ── Офлайн-присутність (тіло гравця, що вийшов посеред матчу) ────────

    /** lib створив тіло: запам'ятовуємо ім'я для ростера на час відсутності власника. */
    public void onOfflineBodySpawned(UUID owner, String name) {
        offlineNames.put(owner, name);
    }

    /**
     * Тіло виживого вбито (удар маньяка, пастка, порожнеча, /kill). Власника вже
     * виключено з матчу: стан, що модулі тримають за його UUID, прибираємо, пастку
     * звільняємо, у підсумок він іде як ELIMINATED. Лут уже випав на стороні lib.
     * Тіло маньяка бути вбитим не може (правила урону не пропускають).
     */
    public void onOfflineBodyKilled(UUID owner, String name) {
        if (context.isManiac(owner)) {
            offlineNames.remove(owner);
            maniacBodyLost = true;
            return;
        }
        if (!context.isSurvivor(owner)) return;
        survivors.onOfflineSurvivorEliminated(owner);
        traps.onOfflineBodyGone(owner);
        context.markEliminated(owner, name);
        noteEliminated(owner);
        offlineNames.remove(owner);
        if (server != null) {
            com.log_to_kot.maniacmod.server.ServerHooks.broadcastRoster(
                server.getPlayerList().getPlayers());
        }
    }

    /** Гравець повернувся до свого тіла живим: ім'я для ростера більше не потрібне. */
    public void onOfflineOwnerReturned(UUID owner) {
        offlineNames.remove(owner);
    }

    /** Те саме «хто в матчі й може отримати удар»: онлайн-гравець або тіло. */
    public boolean isMatchParticipant(UUID id) {
        return context.isManiac(id) || context.isSurvivor(id);
    }

    /**
     * Усі, хто вже пішов з активних виживих цього матчу — втекли чи
     * загинули (для табу й підсумкового екрана). UUID, які повернути з
     * {@link #survivorIds()}, тут не дублюються.
     */
    public List<UUID> terminalSurvivorIds() {
        return context.terminalIds();
    }

    /** Ім'я гравця, що вже вибув (втік/загинув) цього матчу. null, якщо такого немає. */
    /** Зафіксувати влучання маньяка по виживому (для підрахунку «своїх жертв» у табі). */
    public void recordManiacHit(UUID victim, UUID attacker) {
        // Викликається ДО зняття хп, тож hpOf — це хп жертви перед ударом (для хронології).
        int hpBefore = context.hpOf(victim);
        context.recordManiacHit(victim, attacker);
        context.stats().add(attacker, MatchStat.HITS_DEALT, 1);
        context.stats().add(victim, MatchStat.HITS_TAKEN, 1);
        MatchTimeline tl = context.timeline();
        tl.noteHit(victim, attacker);
        tl.add(context.matchTick(), com.log_to_kot.maniacmod.history.HistoryEventType.HIT,
            attacker, victim, "", Math.max(0, hpBefore));
    }

    /** Додає до особистого лічильника гравця (екран підсумків). Безпечно з будь-якої фази. */
    public void recordStat(UUID playerId, MatchStat stat, long amount) {
        context.stats().add(playerId, stat, amount);
        // Лічильники, що мають момент у часі, потрапляють і в хронологію. RESCUES — через recordRescue
        // (там відомо, КОГО врятовано), REPAIR_TICKS — це не подія, а накопичення.
        MatchTimeline tl = context.timeline();
        long now = context.matchTick();
        switch (stat) {
            case DOWNS -> tl.add(now, com.log_to_kot.maniacmod.history.HistoryEventType.DOWNED,
                tl.lastHitterOf(playerId), playerId, "", 0);
            case HOOKS -> tl.add(now, com.log_to_kot.maniacmod.history.HistoryEventType.HOOKED,
                context.maniacUUID(), playerId, "", 0);
            default -> { }
        }
    }

    /** Виживий підняв товариша: лічильник рятівника + подія «хто кого». */
    public void recordRescue(UUID rescuer, UUID rescued) {
        context.stats().add(rescuer, MatchStat.RESCUES, 1);
        context.timeline().add(context.matchTick(), com.log_to_kot.maniacmod.history.HistoryEventType.RESCUED,
            rescuer, rescued, "", 0);
    }

    /** Генератор повністю готовий. {@code worker} — хто добив (може бути null), {@code done} — скільки вже готово. */
    public void recordGeneratorCompleted(UUID worker, String where, int done) {
        context.timeline().add(context.matchTick(),
            com.log_to_kot.maniacmod.history.HistoryEventType.GENERATOR_DONE, worker, null, where, done);
    }

    /** Маніяк поставив пастку типу {@code trapId} (профіль → статистика пасток). */
    public void recordTrapPlaced(UUID maniacId, String trapId) {
        context.stats().addTrap(maniacId, trapId, 1, 0);
        context.timeline().add(context.matchTick(), com.log_to_kot.maniacmod.history.HistoryEventType.TRAP_PLACED,
            maniacId, null, trapId, 0);
    }

    /** Пастка типу {@code trapId} зачепила жертву. Автор — маніяк матчу (він єдиний ставить пастки). */
    public void recordTrapCaught(String trapId, UUID victim) {
        context.stats().addTrap(context.maniacUUID(), trapId, 0, 1);
        MatchTimeline tl = context.timeline();
        tl.noteTrap(victim, trapId);
        tl.add(context.matchTick(), com.log_to_kot.maniacmod.history.HistoryEventType.TRAP_TRIGGERED,
            null, victim, trapId, 0);
    }

    /**
     * Повна статистика матчу для екрана підсумків: час, цілі й рядок на кожного учасника
     * (маніяк, втекли, вибули, ті, кого не врятували). Єдине місце, що збирає ці дані.
     */
    public com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket matchSummary() {
        var stats = context.stats();
        long now = context.matchTick();
        var entries = new ArrayList<com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.Entry>();
        var R = com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.Result.class;

        UUID maniacId = context.maniacUUID();
        if (maniacId != null) {
            entries.add(summaryEntry(maniacId, Enum.valueOf(R, "MANIAC"),
                context.maniacArchetype() == null ? "" : context.maniacArchetype().id(), stats, now));
        }
        for (UUID id : context.escapedIds()) entries.add(summaryEntry(id, Enum.valueOf(R, "ESCAPED"), "", stats, now));
        for (UUID id : context.eliminatedIds()) entries.add(summaryEntry(id, Enum.valueOf(R, "ELIMINATED"), "", stats, now));
        for (UUID id : context.survivorIds()) entries.add(summaryEntry(id, Enum.valueOf(R, "CAUGHT"), "", stats, now));

        int required = ManiacConfigs.get(ConfigSchema.GENERATORS_REQUIRED);
        int done = (int) generators().stream().filter(g -> g.isCompleted()).count();
        int mask = 0;
        for (var objective : completedObjectives()) mask |= 1 << objective.ordinal();

        return new com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket(
            survivorsWon(), (int) (stats.playedTicks(now) / 20L), Math.min(done, required), required, mask, entries);
    }

    private com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.Entry summaryEntry(
            UUID id, com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.Result result,
            String maniacArchetypeId, MatchStats stats, long now) {
        // Гачки маніяка = скільки разів повісили всіх його жертв.
        long hooks = result == com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.Result.MANIAC
            ? stats.total(MatchStat.HOOKS) : stats.get(id, MatchStat.HOOKS);
        return new com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.Entry(
            id, displayNameOf(id), result, maniacArchetypeId,
            (int) (stats.survivedTicks(id, now) / 20L),
            (int) stats.get(id, MatchStat.HITS_DEALT), (int) stats.get(id, MatchStat.HITS_TAKEN),
            // БАГ (у маніяка downs завжди 0): падіння пишуться на ЖЕРТВУ, тож для маніяка це сума по всіх —
            // так само, як гачки вище. Без цього не працювали ні XP «xpManiacDowns», ні «bestDowns» у профілі.
            (int) (result == com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.Result.MANIAC
                ? stats.total(MatchStat.DOWNS) : stats.get(id, MatchStat.DOWNS)), (int) hooks,
            (int) stats.get(id, MatchStat.RESCUES), (int) (stats.get(id, MatchStat.REPAIR_TICKS) / 20L),
            trapLines(stats, id));
    }

    private static java.util.List<com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.TrapLine> trapLines(
            MatchStats stats, UUID id) {
        var out = new ArrayList<com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.TrapLine>();
        for (var e : stats.trapsOf(id).entrySet()) {
            out.add(new com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.TrapLine(
                e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        return out;
    }

    /** Ім'я учасника: онлайн → з профілю; вибулий → термінальне; той, що вийшов → запам'ятоване; інакше «?». */
    public String displayNameOf(UUID id) {
        ServerPlayer online = onlinePlayer(id);
        if (online != null) return online.getGameProfile().getName();
        String terminal = context.terminalDisplayNameOf(id);
        if (terminal != null) return terminal;
        String offline = offlineNames.get(id);
        return offline != null ? offline : "?";
    }

    /** Маньяк, що останнім бив гравця; {@code null}, якщо невідомо. */
    public UUID killerOf(UUID playerId) {
        return context.killerOf(playerId);
    }

    public String terminalDisplayNameOf(UUID playerId) {
        return context.terminalDisplayNameOf(playerId);
    }

    /** ESCAPED або ELIMINATED для гравця, який уже не серед активних виживих. null, якщо такого немає. */
    public SurvivorState terminalStateOf(UUID playerId) {
        return context.terminalStateOf(playerId);
    }

    /** Досягнення матчу (POWER_RESTORED, EXIT_OPENED...) — лише читання. */
    public java.util.Set<MatchObjectives.Objective> completedObjectives() {
        return context.objectives().completed();
    }

    /** Позначає ціль матчу досягнутою. Викликають лише зони/генератори. */
    public void completeObjective(MatchObjectives.Objective objective) {
        if (context.objectives().complete(objective)) {
            context.timeline().add(context.matchTick(),
                com.log_to_kot.maniacmod.history.HistoryEventType.OBJECTIVE, null, null, objective.name(), 0);
        }
    }

    /**
     * Знімок поточної ЦІЛІ матчу для tab-екрана клієнтів: «N з M»
     * генераторів, прогрес кожного й досягнуті віхи. Лише читання —
     * стан не змінює.
     *
     * ── Прогрес одного генератора ──────────────────────────────────
     * Дві стадії зводяться до ОДНІЄЇ шкали 0..100: кожна дає по 50.
     * Яка стадія перша — вирішує сам генератор
     * ({@code GeneratorPoi.stagesDone()}, порядок із
     * {@code generators.stageOrder}), тому шкала росте безперервно за
     * будь-якого порядку, а не «повний → знову порожній» на зміні стадії.
     * Порядок записів — за спаданням прогресу: перші M — ті, що реально
     * наближають вихід (M = GENERATORS_REQUIRED).
     */
    public com.log_to_kot.maniacmod.net.s2c.matchstate.ObjectiveSyncPacket objectiveSnapshot() {
        int required = ManiacConfigs.get(ConfigSchema.GENERATORS_REQUIRED);
        int fuelRequired = Math.max(1, com.log_to_kot.maniacmod.map.zones.GeneratorPoi.fuelRequiredPercent());

        var slots = new ArrayList<com.log_to_kot.maniacmod.net.s2c.matchstate.ObjectiveSyncPacket.GeneratorSlot>();
        int done = 0;
        for (var generator : generators()) {
            int percent;
            if (generator.isCompleted()) {
                percent = 100;
                done++;
            } else {
                int current = generator.stage() == com.log_to_kot.maniacmod.map.zones.GeneratorPoi.Stage.REPAIR
                    ? generator.repairPercent()
                    : Math.min(100, generator.fuelPercent() * 100 / fuelRequired);
                percent = generator.stagesDone() * 50 + current / 2;
            }
            slots.add(new com.log_to_kot.maniacmod.net.s2c.matchstate.ObjectiveSyncPacket.GeneratorSlot(
                generator.stage(), generator.visualState(), Math.max(0, Math.min(100, percent))));
        }
        slots.sort((a, b) -> Integer.compare(b.percent(), a.percent()));

        return new com.log_to_kot.maniacmod.net.s2c.matchstate.ObjectiveSyncPacket(
            Math.min(done, required), required, slots, completedObjectives());
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

    /** Усі зони втечі карти — лише для читання (перевірка, чи виживий утік). */
    public List<com.log_to_kot.maniacmod.map.zones.EscapeZoneArchetype> escapeZones() {
        return context.map().escapeZones();
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

    /**
     * Зведення по ВСІХ гравцях матчу — джерело даних
     * {@link com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket}
     * для tab-екрана. Живих виживих і маньяка бере з поточних мап
     * онлайн-гравців; тих, хто вже втік/загинув, — з
     * {@code terminalIds()} (їх може вже не бути серед {@code players},
     * якщо вони вийшли з гри — таб усе одно повинен показати підсумок).
     *
     * Один метод, а не розсипані по net/ виклики фасаду: сама структура
     * ростера ("як показати роль/hp/стан у одному записі") — знання
     * про матч, тому належить сюди, а не в пакет чи в ServerHooks.
     */
    public List<com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry> rosterEntries(
            List<ServerPlayer> onlinePlayers) {
        var entries = new ArrayList<com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry>();

        // Гравці, що вже вибули (втекли/загинули), обробляються ОКРЕМИМ
        // циклом нижче через terminalSurvivorIds() — навіть якщо вони й
        // досі онлайн (типовий випадок: добитого гравця не кикає з
        // сервера). Без цього skip вибулий онлайн-гравець потрапив би в
        // ростер ДВІЧІ: тут як SPECTATOR (бо isManiac/isSurvivor уже
        // false для нього) і ще раз нижче як SURVIVOR з термінальним
        // станом.
        var terminal = java.util.Set.copyOf(terminalSurvivorIds());

        for (ServerPlayer player : onlinePlayers) {
            UUID id = player.getUUID();
            if (terminal.contains(id)) continue;
            String name = player.getGameProfile().getName();

            if (isManiac(id)) {
                ManiacArchetype maniac = maniacArchetype();
                // Габарити — з СЕРВЕРНОГО конфігу, а не з дефолтів: клієнт
                // має бачити той самий хітбокс, що застосований тут
                // (ManiacBodyEvents). null, якщо архетип ще не обрано.
                var body = maniac == null ? null
                    : new com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.ManiacBody(
                        maniac.hitboxWidth(), maniac.hitboxHeight(), maniac.eyeHeight());
                entries.add(new com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry(
                    id, name, RoleSyncPacket.Role.MANIAC,
                    maniac == null ? "" : maniac.id(), SurvivorState.HEALTHY, 0, 0, body, null));
            } else if (isSurvivor(id)) {
                entries.add(new com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry(
                    id, name, RoleSyncPacket.Role.SURVIVOR, "",
                    survivorStateOf(id), hpOf(id), maxHpOf(id), null, null));
            } else {
                entries.add(new com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry(
                    id, name, RoleSyncPacket.Role.SPECTATOR, "", SurvivorState.HEALTHY, 0, 0, null, null));
            }
        }

        // Учасники, що вийшли й лишили тіло: онлайн-списку в них немає, але в табі вони
        // досі в матчі (виживий зі своїм станом/HP, маньяк зі своїм архетипом).
        var shown = new java.util.HashSet<UUID>();
        for (ServerPlayer player : onlinePlayers) shown.add(player.getUUID());
        for (var offline : offlineNames.entrySet()) {
            UUID id = offline.getKey();
            if (shown.contains(id) || terminal.contains(id)) continue;
            if (!com.log_to_kot.maniacmod.offline.ManiacOffline.hasBody(id)) continue;
            String name = offline.getValue();
            if (isManiac(id)) {
                ManiacArchetype maniac = maniacArchetype();
                var body = maniac == null ? null
                    : new com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.ManiacBody(
                        maniac.hitboxWidth(), maniac.hitboxHeight(), maniac.eyeHeight());
                entries.add(new com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry(
                    id, name, RoleSyncPacket.Role.MANIAC,
                    maniac == null ? "" : maniac.id(), SurvivorState.HEALTHY, 0, 0, body, null));
            } else if (isSurvivor(id)) {
                entries.add(new com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry(
                    id, name, RoleSyncPacket.Role.SURVIVOR, "",
                    survivorStateOf(id), hpOf(id), maxHpOf(id), null, null));
            }
        }

        // Хто вже вибув (втік/загинув), онлайн чи ні — players() міг би
        // і не повернути гравця, що вийшов із сервера, але таб мусить
        // показати підсумок до самого RESET.
        for (UUID id : terminal) {
            String name = terminalDisplayNameOf(id);
            if (name == null) continue;
            entries.add(new com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry(
                id, name, RoleSyncPacket.Role.SURVIVOR, "", terminalStateOf(id), 0, 0, null,
                terminalStateOf(id) == SurvivorState.ELIMINATED ? killerOf(id) : null));
        }

        return entries;
    }

    /** Рядок статусу для /maniac status — єдине місце, що читає кілька полів разом. */
    public String debugStatusLine() {
        return "виживих: " + context.aliveSurvivorCount()
            + " | втекло: " + context.escapedIds().size()
            + " | вибуло: " + context.eliminatedIds().size()
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

    /** Server access for world-level phase modules. */
    public MinecraftServer server() {
        return server;
    }

    // ── Дебаг: morph/unmorph у лобі ─────────────────────────────────────

    /**
     * Перетворити гравця на маньяка в лобі (дебаг-тестування).
     * Надсилає RoleSyncPacket, застосовує 0 слотів хотбару, вимикає
     * StaminaService, якщо гравець щойно був дебаг-виживим, і оновлює
     * табло (RosterSyncPacket) для всіх — інакше Tab-екран показував
     * би стару роль до наступної випадкової події.
     */
    public void morphManiac(ServerPlayer player, ManiacArchetype archetype) {
        if (!phases.is(GamePhase.LOBBY)) return;
        survivors().onLobbyMorphAway(player);
        context.assignManiac(player.getUUID(), archetype);
        com.log_to_kot.maniacmod.net.ModNetwork.toPlayer(player,
            new RoleSyncPacket(RoleSyncPacket.Role.MANIAC, archetype == null ? "" : archetype.id(),
                archetype == null ? 0.0 : archetype.attackRangeBlocks()));
        inventoryAllocation().applyOnJoin(player);
        broadcastRosterAround(player);
        // LOBBY не дозволяє TRAPS (див. GamePhase), тому TrapModule сам
        // не шле цей пакет тут — а екран налаштувань/дебаг-огляду вже
        // може показувати обраного маньяка з його пастками заздалегідь.
        // Каталог пропонуємо теж: дебаг-тестувальник має бачити той
        // самий екран вибору, що й звичайний маньяк у грі.
        if (archetype != null && !traps.autoPickIfNoChoice(player, archetype)) {
            traps.syncCatalogIfChoiceNeeded(player, archetype);
            traps.syncLoadout(player);
        }
    }

    /**
     * Перетворити гравця на виживого в лобі (дебаг-тестування).
     * Надсилає RoleSyncPacket, встановлює слоти виживого, вмикає
     * StaminaService (той самий шлях, що ROLE_REVEAL), шле реальний
     * vitals-знімок і оновлює табло для всіх гравців.
     */
    public void morphSurvivor(ServerPlayer player) {
        if (!phases.is(GamePhase.LOBBY)) return;
        com.log_to_kot.maniacmod.survivors.SurvivorRole role =
            com.log_to_kot.maniacmod.survivors.SurvivorRegistry.defaultRole();
        context.addSurvivor(player.getUUID(), role);
        com.log_to_kot.maniacmod.net.ModNetwork.toPlayer(player,
            new RoleSyncPacket(RoleSyncPacket.Role.SURVIVOR, "", 0.0));
        inventoryAllocation().applyOnJoin(player);
        // Вмикає StaminaService і шле реальний vitals-пакет (hp зі
        // щойно виставленого role.maxHp(), стаміна = 100% бо щойно
        // enableStamina() виставив повну шкалу).
        survivors().onLobbyMorphToSurvivor(player);
        broadcastRosterAround(player);
    }

    /**
     * Зняти роль і повернути гравця в SPECTATOR (дебаг-тестування).
     * Очищає role на сервері й клієнті, скидає слоти до 0, вимикає
     * StaminaService і оновлює табло для всіх гравців.
     */
    public void unmorph(ServerPlayer player) {
        if (!phases.is(GamePhase.LOBBY)) return;
        survivors().onLobbyMorphAway(player);
        context.clearRole(player.getUUID());
        com.log_to_kot.maniacmod.net.ModNetwork.toPlayer(player,
            new RoleSyncPacket(RoleSyncPacket.Role.SPECTATOR, "", 0.0));
        inventoryAllocation().applyOnJoin(player);
        broadcastRosterAround(player);
    }

    /**
     * {@code /maniac debug unmorph} — те саме, що {@link #unmorph}, але
     * дозволено в БУДЬ-ЯКІЙ фазі (LOBBY, ROLE_REVEAL, HUNT, ...), не
     * лише в лобі. Лише для дебаг-зміни ролі: стан самого матчу
     * (фаза, чи є ще маньяк) свідомо НЕ чіпаємо — виконавець сам
     * відповідає за те, що робить це посеред гри.
     *
     * ── Чому не можна просто прибрати {@code phases.is(GamePhase.LOBBY)}
     * з {@link #unmorph} ─────────────────────────────────────────────
     * Звичайний {@code unmorph} у лобі ніколи не мав справи з маньячими
     * модифікаторами (швидкість, лок атаки удару, обраний набір
     * пасток) — вони там просто не активні ({@code ManiacSpeedModule},
     * {@code ManiacCombatModule} ігнорують неігрові фази). Поза лобі ці
     * системи АКТИВНІ, і без явного зняття колишній маньяк лишився б
     * зі швидкістю 1.2, вічним локом атаки на перезарядці й "лип кими"
     * пастками до кінця матчу. Тому дебаг-версія явно кличе
     * {@code maniacSpeed().onPlayerLeft} і {@code combat().onPlayerLeft}
     * (ті самі методи, якими маньяк очищається при діконекті —
     * семантика та сама: "цей гравець більше не маньяк, кому б
     * розсилати стан").
     */
    public void debugUnmorph(ServerPlayer player) {
        maniacSpeed().onPlayerLeft(player);
        combat().onPlayerLeft(player);
        traps().resetChoice();
        com.log_to_kot.maniacmod.net.ModNetwork.toPlayer(player,
            com.log_to_kot.maniacmod.net.s2c.traps.TrapLoadoutPacket.empty());
        survivors().onLobbyMorphAway(player);
        context.clearRole(player.getUUID());
        com.log_to_kot.maniacmod.net.ModNetwork.toPlayer(player,
            new RoleSyncPacket(RoleSyncPacket.Role.SPECTATOR, "", 0.0));
        inventoryAllocation().applyOnJoin(player);
        broadcastRosterAround(player);
    }

    /**
     * {@code /maniac debug swap maniac [гравець]} — перетворює гравця на
     * маньяка в БУДЬ-ЯКІЙ фазі матчу, не лише в лобі (на відміну від
     * {@link #morphManiac}). Якщо гравець щойно був виживим, роль
     * знімається тим самим шляхом, що й {@link #debugUnmorph} — БЕЗ
     * проміжного SPECTATOR: гравець переходить одразу у MANIAC, а не
     * "маньяк → глядач → маньяк" двома командами.
     *
     * Архетип: явно переданий (наприклад раніше обраний), інакше перший
     * з реєстру, інакше {@code null} (як і в debug-старті — гравець сам
     * обирає в меню, якщо фаза це дозволяє).
     */
    public void debugSwapToManiac(ServerPlayer player, ManiacArchetype archetype) {
        UUID previousManiac = context.maniacUUID();
        if (previousManiac != null && !previousManiac.equals(player.getUUID())) {
            // Маньяк уже є, і це не той самий гравець — знімаємо стару
            // роль так само, як debugUnmorph, інакше матч лишився б із
            // двома "маньяками" одночасно (один — за контекстом, другий —
            // за модулями бою/швидкості).
            ServerPlayer oldManiac = server == null ? null
                : server.getPlayerList().getPlayer(previousManiac);
            if (oldManiac != null) releaseManiacModules(oldManiac);
            context.clearRole(previousManiac);
        }
        if (context.isSurvivor(player.getUUID())) {
            survivors().onLobbyMorphAway(player);
            context.clearRole(player.getUUID());
        }

        context.assignManiac(player.getUUID(), archetype);
        com.log_to_kot.maniacmod.net.ModNetwork.toPlayer(player,
            new RoleSyncPacket(RoleSyncPacket.Role.MANIAC, archetype == null ? "" : archetype.id(),
                archetype == null ? 0.0 : archetype.attackRangeBlocks()));
        inventoryAllocation().applyOnJoin(player);
        if (archetype != null) traps.syncCatalogIfChoiceNeeded(player, archetype);
        traps.syncLoadout(player);
        broadcastRosterAround(player);
    }

    /**
     * {@code /maniac debug swap survivor [гравець]} — перетворює гравця
     * на виживого в БУДЬ-ЯКІЙ фазі матчу, не лише в лобі (на відміну від
     * {@link #morphSurvivor}). Якщо гравець щойно був маньяком, роль
     * знімається тим самим шляхом, що й {@link #debugUnmorph} — БЕЗ
     * проміжного SPECTATOR.
     */
    public void debugSwapToSurvivor(ServerPlayer player) {
        if (context.isManiac(player.getUUID())) {
            releaseManiacModules(player);
            context.clearRole(player.getUUID());
        }

        com.log_to_kot.maniacmod.survivors.SurvivorRole role =
            com.log_to_kot.maniacmod.survivors.SurvivorRegistry.defaultRole();
        context.addSurvivor(player.getUUID(), role);
        com.log_to_kot.maniacmod.net.ModNetwork.toPlayer(player,
            new RoleSyncPacket(RoleSyncPacket.Role.SURVIVOR, "", 0.0));
        inventoryAllocation().applyOnJoin(player);
        survivors().onLobbyMorphToSurvivor(player);
        broadcastRosterAround(player);
    }

    /**
     * Знімає з гравця всі маньячі модифікатори (швидкість, лок атаки,
     * обрані пастки) тим самим шляхом, що {@link #debugUnmorph} і
     * {@code onPlayerLeft} для маньяка — спільний хвіст для будь-якого
     * "цей гравець більше не маньяк" поза лобі.
     */
    private void releaseManiacModules(ServerPlayer player) {
        maniacSpeed().onPlayerLeft(player);
        combat().onPlayerLeft(player);
        traps().resetChoice();
        com.log_to_kot.maniacmod.net.ModNetwork.toPlayer(player,
            com.log_to_kot.maniacmod.net.s2c.traps.TrapLoadoutPacket.empty());
    }

    /**
     * Табло читається з поточного {@code MatchContext}, тому досить
     * розіслати всім онлайн — той самий список, що
     * {@code /maniac morph} і так вимагає через requireMatch(LOBBY).
     * player.getServer() тут завжди не-null: гравець-виконавець
     * команди онлайн за визначенням.
     */
    private void broadcastRosterAround(ServerPlayer player) {
        if (player.getServer() == null) return;
        com.log_to_kot.maniacmod.server.ServerHooks.broadcastRoster(
            player.getServer().getPlayerList().getPlayers());
    }

    /** Гравці в режимі Spectator не беруть участі в наступному матчі. */
    public List<ServerPlayer> eligiblePlayers(List<ServerPlayer> onlinePlayers) {
        return onlinePlayers.stream()
            .filter(player -> !player.isSpectator())
            .toList();
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
        lastStartFailure = null;
        if (!phases.is(GamePhase.LOBBY)) return false;
        List<ServerPlayer> participants = eligiblePlayers(players);

        // Дебаг: один гравець — це вже повноцінний матч (див. DebugMode).
        int minPlayers = DebugMode.enabled() ? 1 : ManiacConfigs.get(ConfigSchema.MIN_PLAYERS);
        if (participants.size() < minPlayers) return false;
        // maniacPlayer == null дозволено: це дебаг-режим "ти виживий" —
        // матч іде без маньяка взагалі (треба для перевірки слотів,
        // стаміни, генераторів і луту).
        if (maniacPlayer != null && !participants.contains(maniacPlayer)) return false;

        List<com.log_to_kot.maniacmod.spawn.SpawnPoint> configuredPoints =
            List.copyOf(context.spawnPoints());
        releaseSpawnPoints(configuredPoints);

        MatchContext fresh = new MatchContext();
        if (maniacPlayer != null) {
            fresh.assignManiac(maniacPlayer.getUUID(), maniacArchetype);
        }
        for (var point : configuredPoints) fresh.addSpawnPoint(point);

        List<UUID> survivorIds = new ArrayList<>();
        for (ServerPlayer p : participants) {
            if (maniacPlayer != null && p.getUUID().equals(maniacPlayer.getUUID())) continue;
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
        if (!DebugMode.enabled()
            && !fresh.spawnPoints().isEmpty() && survivorPoints < survivorIds.size()) {
            lastStartFailure = "Точок для виживих " + survivorPoints
                + ", а гравців " + survivorIds.size()
                + ". Одна точка не може вмістити двох — додай ще точок.";
            return false;
        }

        // Склад для історії: маніяк першим — індекс у хронології збігається з індексом у записі матчу.
        MatchTimeline timeline = fresh.timeline();
        if (maniacPlayer != null) timeline.register(maniacPlayer.getUUID(), maniacPlayer.getGameProfile().getName());
        for (ServerPlayer p : participants) {
            if (maniacPlayer != null && p.getUUID().equals(maniacPlayer.getUUID())) continue;
            timeline.register(p.getUUID(), p.getGameProfile().getName());
        }
        timeline.add(0L, com.log_to_kot.maniacmod.history.HistoryEventType.MATCH_START, null, null,
            maniacArchetype == null ? "" : maniacArchetype.id(), participants.size());

        MatchContext previous = this.context;
        this.context = fresh;
        pendingSpawnPlan = null;
        plannerReady = false;
        scatterApplied = false;
        scatterFailed = false;

        try {
            advanceTo(GamePhase.CINEMATIC, players);
        } catch (SpawnPlanner.SpawnPlanFailure failure) {
            // Раніше причина тут губилась: адмін бачив лише «не вдалося
            // почати матч» і мусив вгадувати, чого не вистачає.
            lastStartFailure = failure.getMessage();
            ManiacMod.LOGGER.warn("[match] старт скасовано: {}", failure.getMessage());
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

    /** Причина останньої невдалої спроби {@link #start}, якщо це розмітка карти; інакше null. */
    public String lastStartFailure() {
        return lastStartFailure;
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
        // Мітки часу для «скільки грали»: старт — перший HUNT, кінець — перший тік сцени/ENDING.
        if (phases.is(GamePhase.HUNT)) context.stats().markPlayStart(context.matchTick());
        if ((phases.is(GamePhase.VICTORY_SCENE) || phases.is(GamePhase.ENDING))
            && context.stats().hasPlayStarted()) {
            context.stats().markPlayEnd(context.matchTick());
        }
        if (phases.is(GamePhase.LOBBY)) tickLobbyHunger(players);
        phases.tick(players);
        checkPhaseExitConditions(players);
    }

    /**
     * БАГ (гравці втрачали голод у лобі): {@code lib.lobbyModule()
     * .sendToLobby(player)} ставить гейммод {@code ADVENTURE}, а не
     * {@code SPECTATOR}/{@code CREATIVE} — і в {@code ADVENTURE} голод
     * тратиться так само, як у ваніли, бо гейммод сам по собі його не
     * зупиняє (це робить лише {@code SPECTATOR}/{@code CREATIVE}).
     *
     * У виживих під час матчу голод тримає бібліотека сама
     * ({@code StaminaRules.forceFullHungerWhileActive(true)} —
     * {@code SurvivorModule.buildStaminaRules}), але цей прапор діє
     * лише поки {@code StaminaService} активний для гравця, а в лобі
     * він узагалі не вмикається (без ролі стаміна нікому не потрібна —
     * див. коментар при {@code .withStamina(...active(false)...)} у
     * {@code ManiacMod}). Тому в лобі голод не тримав ніхто.
     *
     * Найпростіше й найдешевше виправлення — тримати шкалу голоду на
     * максимумі напряму, тим самим щотіковим підходом, що вже описаний
     * для стаміни з поламаною ногою (SurvivorModule): постійна
     * перезапис дешевший і надійніший, ніж ловити кожну подію
     * виснаження (стрибки, регенерація хп) окремо.
     */
    private void tickLobbyHunger(List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            var food = player.getFoodData();
            if (food.getFoodLevel() < 20) food.setFoodLevel(20);
            if (food.getSaturationLevel() < 5.0f) food.setSaturation(5.0f);
        }
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
            // Кіно-вступ (IntroModule) триває IntroTimeline.cinematicTicks();
            // 0 — вступ вимкнено (game.introEnabled), і фаза, як раніше, живе
            // один тік, за який готується план розкидання.
            if (!plannerReady) {
                try {
                    prepareSpawnPlan(players);
                } catch (SpawnPlanner.SpawnPlanFailure failure) {
                    reset(players);
                    return;
                }
            }
            if (plannerReady && phases.ticksInPhase() >= com.log_to_kot.maniacmod.intro.IntroTimeline.cinematicTicks()) {
                advanceTo(GamePhase.SCATTER, players);
            }
            return;
        }

        if (phases.is(GamePhase.SCATTER)) {
            if (scatterFailed) {
                if (DebugMode.enabled()) {
                    // Дебаг: розкидання не вдалось (немає карти/точок) — не
                    // скидаємо матч, ідемо далі й дивимось механіки.
                    advanceTo(GamePhase.ROLE_REVEAL, players);
                } else {
                    reset(players);
                }
                return;
            }
            // Після телепорту екран ще чорний: тримаємо SCATTER, поки клієнти
            // догружають чанки, а IntroModule не поверне контроль (див. IntroTimeline).
            if (scatterApplied && phases.ticksInPhase()
                >= com.log_to_kot.maniacmod.intro.IntroTimeline.scatterHoldTicks()) {
                advanceTo(GamePhase.ROLE_REVEAL, players);
            }
            return;
        }

        if (phases.is(GamePhase.ROLE_REVEAL)) {
            long held = phases.ticksInPhase();
            // Гра НЕ чекає на вибір пасток: маньяк може обирати й під час
            // полювання (меню лишається відкритим), а до підтвердження в
            // нього просто немає пасток — автовибору більше немає.
            if (held >= roleRevealTicks()) advanceTo(GamePhase.HUNT, players);
            return;
        }

        if (phases.is(GamePhase.VICTORY_SCENE)) {
            // Сцена веде себе сама (VictorySceneModule); тут лише передача естафети в ENDING.
            if (victory.isFinished()) advanceTo(GamePhase.ENDING, players);
            return;
        }

        if (phases.is(GamePhase.ENDING)) {
            // Усі втекли — матч іде в ENDING одразу, а вертоліт ще підтягує мотузку й відлітає.
            // Не скидаємо світ, поки він не зникне (з запобіжником, щоб ніщо не зависло).
            long held = phases.ticksInPhase();
            boolean heliStillLeaving = evac.isDeparting() && held < endingTicks() + HELI_LEAVE_GRACE_TICKS;
            if (held >= endingTicks() && !heliStillLeaving) reset(players);
            return;
        }

        if (!phases.isGameplay()) return;

        // Маньяка (його тіло) знищено, а власник офлайн: матч без маньяка не має сенсу.
        if (maniacBodyLost) {
            maniacBodyLost = false;
            if (!DebugMode.enabled()) {
                reset(players);
                return;
            }
        }

        // Дебаг: 0 виживих (або 0 маньяків) НЕ завершує матч — інакше
        // одиночна перевірка за маньяка кидала б у ENDING на першому ж тіку.
        if (!DebugMode.enabled() && noSurvivorsLeft()) {
            concludeMatch(players);
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
            // POWERED → HELI_WAIT: виживі викликали вертоліт з приймача.
            case POWERED -> {
                if (objectives.isComplete(MatchObjectives.Objective.HELI_CALLED)) {
                    advanceTo(GamePhase.HELI_WAIT, players);
                }
            }
            // HELI_WAIT → FINALE: мотузка спущена й готова (EXIT_OPENED ставить EvacModule).
            case HELI_WAIT -> {
                if (objectives.isComplete(MatchObjectives.Objective.EXIT_OPENED)) {
                    advanceTo(GamePhase.FINALE, players);
                }
            }
            // FINALE → ENDING: вертоліт відлетів. Хто не встиг — програв; хто втік — виграв.
            // (Усі втекли/вибули раніше — ENDING настане через noSurvivorsLeft вище.)
            case FINALE -> {
                if (evac.isFinished()) concludeMatch(players);
            }
            default -> { }
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
        if (context.aliveSurvivorCount() == 0) return true;
        // Усі, хто лишився в матчі, офлайн (стоять тілами): грати нікому, маньяк виграв.
        // Живий виживий у складі без онлайн-гравця можливий лише як тіло, бо без тіла
        // вихід прибирає його зі складу (див. onPlayerLeft).
        for (UUID id : context.survivorIds()) {
            if (onlinePlayer(id) != null) return false;
        }
        return true;
    }

    /**
     * Тривалість CINEMATIC. {@code 0} (дефолт) — фазу пропущено: вона
     * живе рівно один тік, за який {@link MatchStartCoordinator} встигає
     * підготувати план, і одразу йде SCATTER. Так матч стартує моментально
     * з повним розподілом (гравці + генератори + точки луту), поки самої
     * кінематики немає; коли з'явиться — достатньо підняти
     * {@code cinematicSeconds} у maniac.yml, код міняти не треба.
     */
    private int roleRevealTicks() {
        // + повільне розтемнення після вступу: пробудження й роль не мають обрізатись.
        return ManiacConfigs.get(ConfigSchema.ROLE_REVEAL_SECONDS) * 20
            + com.log_to_kot.maniacmod.intro.IntroTimeline.roleRevealExtraTicks();
    }

    /** Скільки тіків ENDING може тривати понад endingSeconds, чекаючи, поки вертоліт відлетить. */
    private static final long HELI_LEAVE_GRACE_TICKS = 20L * 40L;

    private int endingTicks() {
        return ManiacConfigs.get(ConfigSchema.ENDING_SECONDS) * 20;
    }

    /**
     * Планувальник поки що синхронний навмисно. Контракт уже відокремлений
     * від таймера кінематики, тому пізніше виконання можна винести в
     * executor без зміни правил переходу фаз.
     */
    private void prepareSpawnPlan(List<ServerPlayer> players) {
        if (plannerReady) return;
        try {
            pendingSpawnPlan = buildSpawnPlan();
        } catch (SpawnPlanner.SpawnPlanFailure failure) {
            if (!DebugMode.enabled()) throw failure;
            // Дебаг: карти може ще не бути взагалі. Матч усе одно стартує —
            // просто без телепорту й без генераторів (порожній план).
            // Гравець мусить побачити ЦЕ повідомлення в чаті, а не лише
            // в лог-файлі сервера: без нього виглядає так, ніби команда
            // старту матчу "нічого не робить" (точки стоять, а телепорту
            // немає), хоча насправді план просто мовчки пропущено.
            ManiacMod.LOGGER.warn("[debug] план розкидання не побудовано: {} — матч іде без нього (debugMode=true)",
                failure.getMessage());
            for (ServerPlayer player : players) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "maniacmod.command.debug_scatter_skipped", failure.getMessage()));
            }
            // Не порожній план, а «що є, те й спавнимо»: немає точки маньяка чи бракує точок
            // виживих — генератори, лут, приймач і гаки все одно мають з'явитись.
            try {
                pendingSpawnPlan = spawnPlanner.planDebugBestEffort(
                    context.spawnPoints(), context.survivorIds());
            } catch (RuntimeException fallbackFailure) {
                ManiacMod.LOGGER.warn("[debug] дебаг-план теж не вдалося побудувати: {}",
                    fallbackFailure.toString());
                pendingSpawnPlan = SpawnPlanner.SpawnPlan.empty();
            }
        }
        plannerReady = true;
    }

    /**
     * План розкидання для поточного складу матчу.
     *
     * <p>Три випадки: повний матч (є маньяк з архетипом), дебаг "ти
     * виживий" (маньяка немає), і матч, у якому архетип маньяка ще не
     * обрано (MENU) — в останньому точок його архетипу фізично не існує,
     * тому беремо план без маньяка, а не падаємо з NPE на {@code .id()}.</p>
     */
    private SpawnPlanner.SpawnPlan buildSpawnPlan() {
        ManiacArchetype archetype = context.maniacArchetype();
        if (context.maniacUUID() == null || archetype == null) {
            return spawnPlanner.planSurvivorsOnly(context.spawnPoints(), context.survivorIds());
        }
        // numManiacs = 1: зараз матч підтримує рівно одного маньяка
        // (context.maniacUUID() — скаляр). Коли з'явиться підтримка
        // двох маньяків одночасно, тут достатньо підставити реальну
        // кількість — формула бонусних генераторів у SpawnPlanner уже
        // готова, змінювати саму логіку розкидання не треба.
        return spawnPlanner.plan(
            context.spawnPoints(), archetype.id(), context.survivorIds(), 1);
    }

    private boolean applySpawnPlan(List<ServerPlayer> players) {
        if (pendingSpawnPlan == null) return false;

        // Маньяка може не бути взагалі (дебаг "ти виживий") — тоді просто
        // нікого не телепортуємо як маньяка, і це не помилка плану.
        ServerPlayer maniac = context.maniacUUID() == null
            ? null : findPlayer(players, context.maniacUUID());
        if (context.maniacUUID() != null && maniac == null) return false;
        for (UUID survivorId : pendingSpawnPlan.survivorPoints().keySet()) {
            if (findPlayer(players, survivorId) == null) return false;
        }

        if (maniac != null && pendingSpawnPlan.maniacPoint() != null) {
            teleportToSpawn(maniac, pendingSpawnPlan.maniacPoint());
            context.rememberSpawn(maniac.getUUID(), pendingSpawnPlan.maniacPoint());
        }

        for (var entry : pendingSpawnPlan.survivorPoints().entrySet()) {
            ServerPlayer survivor = findPlayer(players, entry.getKey());
            teleportToSpawn(survivor, entry.getValue());
            context.rememberSpawn(entry.getKey(), entry.getValue());
        }

        for (var point : pendingSpawnPlan.engagedItemPoints()) point.engage();
        context.map().clear();

        // Рівень для спавну сутностей генераторів — беремо в першого-
        // ліпшого відомого гравця матчу (усі вони на одному рівні гри),
        // а не через MinecraftServer.overworld(): матч у принципі не
        // прив'язаний саме до overworld.
        ServerLevel generatorLevel = maniac != null ? maniac.serverLevel()
            : players.isEmpty() ? null : players.get(0).serverLevel();

        for (var point : pendingSpawnPlan.generatorPoints()) {
            GeneratorPoi poi = new GeneratorPoi(point.pos());
            context.map().addGenerator(poi);
            spawnGeneratorEntity(generatorLevel, point.pos());
        }

        // Предмети на задіяних ITEM-точках. Точки вже позначені engage()
        // вище; рівень той самий, що й для генераторів.
        loot.spawnOnPoints(generatorLevel, pendingSpawnPlan.engagedItemPoints());

        // Евакуація: єдиний приймач матчу + гарантовані сигнальні ракети.
        evac.spawnReceiver(generatorLevel, pendingSpawnPlan.receiverPoint());
        List<com.log_to_kot.maniacmod.spawn.SpawnPoint> allItemPoints = new ArrayList<>();
        for (var sp : context.spawnPoints()) {
            if (sp.kind() == SpawnPointKind.ITEM) allItemPoints.add(sp);
        }
        evac.spawnFlares(generatorLevel, allItemPoints);

        // Гаки: спавняться ВСІ розмічені (на відміну від приймача — одного на матч).
        hooks.spawnHooks(generatorLevel, context.spawnPoints());
        return true;
    }

    /**
     * Спавнить сутність генератора в потрібній точці — заміна колишньої
     * моделі, де {@code GeneratorBlock} мав бути заздалегідь поставлений
     * картобудівником уручну на карті. Тепер розмітка (SpawnPoint типу
     * GENERATOR) — єдине, що потрібно від карти; сама сутність з'являється
     * при застосуванні плану, з випадковим поворотом по X (0 по Y), так
     * само як {@link com.log_to_kot.maniacmod.entity.GroundItemEntity}.
     *
     * @param level може бути {@code null} лише в теоретичному випадку
     *              порожнього списку гравців і відсутнього маньяка —
     *              тоді спавн просто пропускається (генератор лишається
     *              суто логічним записом {@link GeneratorPoi}, без
     *              видимого тіла, доки не буде явного рівня для спавну).
     * @param pos   позиція з розмітки картобудівника (SpawnPoint GENERATOR).
     */
    private void spawnGeneratorEntity(ServerLevel level, BlockPos pos) {
        if (level == null) return;
        float rotationX = rng.nextFloat() * 360f;
        com.log_to_kot.maniacmod.entity.GeneratorEntity.spawn(
            com.log_to_kot.maniacmod.registry.ModEntityTypes.GENERATOR.get(),
            level,
            pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
            rotationX);
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
        // Провал будь-якої відкритої міні-гри ремонту — незалежно від
        // ролі/фази нижче: гравець фізично зник із сервером зв'язку,
        // тож екран міні-гри (якщо був) уже нікому показувати.
        generatorModule().onPlayerLeftDuringMinigame(player.getUUID());
        // Лазив по мотузці — зняти з носія ДО того, як lib залишить тіло в повітрі.
        evac.onPlayerLeft(player);
        // Несе/висить — зняти сидіння ДО того, як survivors забуде гравця (тіло вже створене lib).
        hooks.onPlayerLeft(player);
        // Лок руху, прогрес вставання, правила стаміни — усе за UUID.
        survivors.onPlayerLeft(player);
        traps.onPlayerLeft(player);
        maniacStun.onPlayerLeft(player);
        maniacSpeed.onPlayerLeft(player);

        List<ServerPlayer> online = player.getServer().getPlayerList().getPlayers();
        // Тіло створює lib на HIGHEST logout, раніше за цей виклик: якщо воно стоїть,
        // гравець лишається учасником матчу, а не «вибуває».
        boolean hasBody = com.log_to_kot.maniacmod.offline.ManiacOffline.hasBody(player.getUUID());
        noteLeft(player.getUUID(), hasBody);
        if (context.isManiac(player.getUUID())) {
            // Лок ATTACK і перезарядка прив'язані до UUID, а не до списку
            // онлайн-гравців — знімаємо їх ДО можливого reset(...) нижче.
            combat.onPlayerLeft(player);
            // Маньяк із тілом: матч іде далі, тіло стоїть там, де він вийшов.
            if (hasBody) return;
            // Дебаг: вихід/перезахід не має скидати тобі матч наодинці.
            // Сцена перемоги: маніяк уже переміг — вихід не скидає матч, сцена сама прибере учасника.
            if (!DebugMode.enabled()
                && !phases.is(GamePhase.LOBBY) && !phases.is(GamePhase.RESET)
                && !phases.is(GamePhase.VICTORY_SCENE)) reset(online);
            return;
        }

        if (phases.is(GamePhase.LOBBY) || phases.is(GamePhase.RESET)
            || phases.is(GamePhase.ENDING) || phases.is(GamePhase.VICTORY_SCENE)) return;

        // Виживий із тілом лишається у складі: пастка, удар маньяка й лут працюють
        // по тілу, а «усі офлайн» завершує матч у checkPhaseExitConditions.
        if (hasBody) return;

        context.removeSurvivor(player.getUUID());
        pendingSpawnPlan = null;
        plannerReady = false;
        scatterApplied = false;
        scatterFailed = phases.is(GamePhase.SCATTER);

        // Дебаг: 0 виживих не завершує матч (див. DebugMode).
        if (!DebugMode.enabled() && context.aliveSurvivorCount() == 0) {
            concludeMatch(online);
        }
    }

    /** Що вийшло з {@link #forceEnd}. */
    public enum ForceEndResult {
        /** Не ігрова фаза (лобі, вступ, підсумки, скидання) — завершувати нічого. */
        REJECTED,
        /** Перемога маніяка: піде сцена «гачки з тілами» (VICTORY_SCENE), далі екран підсумків. */
        MANIAC_SCENE,
        /** Перемога маніяка, але сцену вимкнено/не розмічено — одразу екран підсумків. */
        MANIAC_RESULT_ONLY,
        /** Перемога виживих: усі, хто лишився, «втікають» (з анонсом), далі екран підсумків. */
        SURVIVORS
    }

    /**
     * Адмінське примусове завершення матчу ({@code /maniac win}). Іде тим самим шляхом, що й
     * природний кінець: {@link #concludeMatch} → {@code VICTORY_SCENE} (маніяк, якщо вона
     * доступна) або {@code ENDING} (екран підсумків із титулом переможця).
     *
     * <p>Виживі: кожен, хто ще в грі, фіксується втеклим тим самим шляхом, що й лазіння по
     * мотузці ({@code EvacModule.forceEscape}) — із титром «втік!» усім і табом. Маніяк: результат
     * примусово «не втік ніхто», навіть якщо хтось уже втік раніше; лазіння по мотузці скасовується,
     * гачки/ноші знімає сама фаза VICTORY_SCENE.
     */
    public ForceEndResult forceEnd(boolean survivorsWin, List<ServerPlayer> players) {
        if (!phases.isGameplay()) return ForceEndResult.REJECTED;
        context.timeline().add(context.matchTick(), com.log_to_kot.maniacmod.history.HistoryEventType.FORCE_END,
            null, null, survivorsWin ? "survivors" : "maniac", 0);

        ForceEndResult result;
        if (survivorsWin) {
            for (UUID id : context.survivorIds()) {
                ServerPlayer online = onlinePlayer(id);
                if (online != null) {
                    evac.forceEscape(online);
                } else {
                    // Живий виживий без онлайн-гравця — стоїть тілом: фіксуємо втечу за UUID.
                    String name = offlineNames.getOrDefault(id, "?");
                    survivors.onOfflineSurvivorEliminated(id);
                    traps.onOfflineBodyGone(id);
                    context.markEscaped(id, name);
                    noteEscaped(id);
                    offlineNames.remove(id);
                }
            }
            context.forceOutcome(true);
            result = ForceEndResult.SURVIVORS;
        } else {
            evac.cancelClimbs();
            context.forceOutcome(false);
            result = victory.canPlay(players) ? ForceEndResult.MANIAC_SCENE : ForceEndResult.MANIAC_RESULT_ONLY;
        }
        com.log_to_kot.maniacmod.server.ServerHooks.broadcastRoster(players);
        concludeMatch(players);
        return result;
    }

    /**
     * ЄДИНЕ місце, де матч іде до підсумків. Перемога маніяка (не втік ніхто) веде спершу у сцену
     * перемоги, якщо вона ввімкнена й карта розмічена; усе інше — одразу в ENDING. Сцена сама
     * передає естафету в ENDING (див. {@code checkPhaseExitConditions}).
     */
    private void concludeMatch(List<ServerPlayer> players) {
        if (victory.canPlay(players)) {
            advanceTo(GamePhase.VICTORY_SCENE, players);
        } else {
            advanceTo(GamePhase.ENDING, players);
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

        // roleSyncFor читає archetype ЩОЙНО ПІСЛЯ assignManiac вище: до
        // вибору в меню архетип у клієнта був "" (RoleSyncPacket на вхід
        // у ROLE_REVEAL шлеться з archetype ще null — MENU-режим). Без
        // повторної відправки тут archetypeId лишався б порожнім аж до
        // наступного переходу фази, і екран вибору пасток не мав би на
        // основі чого відкритись.
        com.log_to_kot.maniacmod.net.ModNetwork.toPlayer(player, roleSyncFor(player));
        // MENU-режим: архетип щойно став відомим лише зараз (на вході в
        // ROLE_REVEAL його ще не було). Пропонуємо вибір пасток, якщо є
        // з чого — той самий шлях, що RANDOM/FIXED проходять одразу на
        // вході в фазу (там архетип відомий раніше).
        if (archetype != null) traps.syncCatalogIfChoiceNeeded(player, archetype);
        traps.syncLoadout(player);
    }

    public void reset(List<ServerPlayer> players) {
        // Матч скинуто, не дійшовши до підсумків (маніяк вийшов, збій розкидання): це теж частина історії.
        archiveIfAborted();
        if (server != null) MatchRuntimeRegistry.cleanup(server);
        if (server != null) MatchBlockRegistry.restoreAll(server);
        releaseSpawnPoints(context.spawnPoints());
        pendingSpawnPlan = null;
        plannerReady = false;
        scatterApplied = false;
        scatterFailed = false;
        advanceTo(GamePhase.RESET, players);
        this.context = new MatchContext();
        offlineNames.clear();
        maniacBodyLost = false;
        advanceTo(GamePhase.LOBBY, players);
        // БАГ (точки є в points.yml, а генератори не з'являються з ДРУГОГО
        // матчу): новий MatchContext порожній, а розмітку в нього
        // підвантажували лише при старті сервера, командах і зміні
        // файлу. Тобто після першого завершеного (чи скинутого) матчу
        // SpawnPlanner бачив нуль точок — план не будувався, а точки в
        // файлі лежали недоторкані. Тепер розмітка повертається щоразу,
        // коли ми знову опиняємось у лобі.
        //
        // Після advanceTo(LOBBY), а не до: reloadConfiguredMap нічого не
        // робить поза фазою LOBBY (не чіпає розмітку посеред матчу).
        reloadConfiguredMap();
    }

    // ── Історія матчів: запис ────────────────────────────────────────────

    private void noteEscaped(UUID id) {
        MatchTimeline tl = context.timeline();
        long now = context.matchTick();
        tl.add(now, com.log_to_kot.maniacmod.history.HistoryEventType.ESCAPED, id, null, "", 0);
        tl.markOut(id, now, "");
    }

    private void noteEliminated(UUID id) {
        MatchTimeline tl = context.timeline();
        long now = context.matchTick();
        String cause = tl.causeOf(id);
        tl.add(now, com.log_to_kot.maniacmod.history.HistoryEventType.ELIMINATED,
            tl.lastHitterOf(id), id, cause, 0);
        tl.markOut(id, now, cause);
    }

    /** Вихід учасника посеред матчу. Без тіла — він вибуває зі складу («left»); з тілом лишається в грі. */
    private void noteLeft(UUID id, boolean hasBody) {
        if (!context.isManiac(id) && !context.isSurvivor(id)) return;
        if (phases.is(GamePhase.LOBBY) || phases.is(GamePhase.RESET)
            || phases.is(GamePhase.ENDING) || phases.is(GamePhase.VICTORY_SCENE)) return;
        MatchTimeline tl = context.timeline();
        long now = context.matchTick();
        tl.add(now, com.log_to_kot.maniacmod.history.HistoryEventType.PLAYER_LEFT, id, null,
            hasBody ? "body" : "gone", 0);
        if (!hasBody) tl.markOut(id, now, "left");
    }

    /** Як скінчився матч, що дійшов до підсумків. */
    private com.log_to_kot.maniacmod.history.MatchRecord.End resolveEnd() {
        if (context.forcedSurvivorsWin() != null) return com.log_to_kot.maniacmod.history.MatchRecord.End.FORCED;
        if (survivorsWon()) return com.log_to_kot.maniacmod.history.MatchRecord.End.ESCAPE;
        return context.aliveSurvivorCount() == 0
            ? com.log_to_kot.maniacmod.history.MatchRecord.End.ALL_OUT
            : com.log_to_kot.maniacmod.history.MatchRecord.End.TIME_OUT;
    }

    /** Скинутий матч, що встиг дійти до полювання, записується як перерваний (без XP). */
    private void archiveIfAborted() {
        MatchTimeline tl = context.timeline();
        if (tl.isClosed() || !context.stats().hasPlayStarted()) return;
        archiveMatch(matchSummary(), null, com.log_to_kot.maniacmod.history.MatchRecord.End.ABORTED);
    }

    /**
     * ЄДИНЕ місце, де матч потрапляє в історію. Закриває хронологію (повторно не запишеться) і пропускає:
     * вимкнену історію, дебаг (одиночні перевірки не засмічують історію справжніх ігор) і матч, що не
     * дійшов до полювання.
     */
    private void archiveMatch(com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket summary,
                              java.util.Map<UUID, com.log_to_kot.maniacmod.net.s2c.matchstate.MatchRewardPacket> rewards,
                              com.log_to_kot.maniacmod.history.MatchRecord.End end) {
        MatchTimeline tl = context.timeline();
        if (tl.isClosed()) return;
        tl.noteDebug();
        tl.close();
        if (server == null || !ManiacConfigs.get(ConfigSchema.HISTORY_ENABLED)) return;
        if (tl.debugTainted()) {
            ManiacMod.LOGGER.info("[history] матч не записано: був увімкнений дебаг-режим");
            return;
        }
        if (!context.stats().hasPlayStarted()) return;
        try {
            history.archive(server, MatchRecordBuilder.build(context, summary, rewards, end));
        } catch (RuntimeException e) {
            // Збій історії не має зривати кінець матчу (підсумки, XP, повернення в лобі).
            ManiacMod.LOGGER.error("[history] не вдалося записати матч", e);
        }
    }

    /** Зупинка сервера — знімаємо статичне посилання, щоб не текло. */
    public void shutdown() {
        history.close();
        if (server != null) MatchRuntimeRegistry.cleanup(server);
        if (server != null) MatchBlockRegistry.restoreAll(server);
        server = null;
        Phases.unbind();
    }
}
