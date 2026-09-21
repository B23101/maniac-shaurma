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
        phases.register(worldEnvironment);
    }

    /** Модуль генераторів — для GeneratorEntity і ServerPacketHandler. */
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
    private final class PhaseNetworkSync implements PhaseListener {
        @Override public String id() { return "phase-sync"; }

        @Override
        public void onPhaseEnter(GamePhase phase, java.util.List<ServerPlayer> players) {
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
                archetype == null ? "" : archetype.id());
        }
        if (isSurvivor(id)) {
            var role = survivorRoleOf(id);
            return new RoleSyncPacket(RoleSyncPacket.Role.SURVIVOR,
                role == null ? "" : role.id());
        }
        return new RoleSyncPacket(RoleSyncPacket.Role.SPECTATOR, "");
    }

    /** Узгоджує підготовку плану і фізичне застосування розкидання. */
    private final class MatchStartCoordinator implements PhaseListener {
        @Override public String id() { return "match-start"; }

        @Override
        public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
            if (phase == GamePhase.CINEMATIC) {
                prepareSpawnPlan(players);
            } else if (phase == GamePhase.SCATTER) {
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
                    ManiacMod.lib().lobbyModule().sendToLobby(player);
                    ManiacMod.lib().lobbyModule().hideNameTag(player);
                }
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

    /**
     * Усі гравці, що зараз на сервері (виживі, маньяк, глядачі). Для подій,
     * які мають побачити ВСІ, — наприклад вибух генератора. Порожній
     * список, якщо сервер ще не підключено (до старту або після скидання).
     */
    public List<ServerPlayer> onlinePlayers() {
        return server == null ? List.of() : List.copyOf(server.getPlayerList().getPlayers());
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

    /** Хто вибув остаточно (непритомний не дочекався підняття) цього матчу. */
    public List<UUID> eliminatedSurvivorIds() {
        return context.eliminatedIds();
    }

    /** Гравець дійшов до зони втечі здоровим — фіксує втечу. */
    public void markEscaped(ServerPlayer player) {
        context.markEscaped(player.getUUID(), player.getGameProfile().getName());
    }

    /** Непритомний помер — гравець вибуває з матчу остаточно. */
    public void markEliminated(ServerPlayer player) {
        context.markEliminated(player.getUUID(), player.getGameProfile().getName());
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
                String archetypeId = maniacArchetype() == null ? "" : maniacArchetype().id();
                entries.add(new com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry(
                    id, name, RoleSyncPacket.Role.MANIAC, archetypeId, SurvivorState.HEALTHY, 0, 0));
            } else if (isSurvivor(id)) {
                entries.add(new com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry(
                    id, name, RoleSyncPacket.Role.SURVIVOR, "",
                    survivorStateOf(id), hpOf(id), maxHpOf(id)));
            } else {
                entries.add(new com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry(
                    id, name, RoleSyncPacket.Role.SPECTATOR, "", SurvivorState.HEALTHY, 0, 0));
            }
        }

        // Хто вже вибув (втік/загинув), онлайн чи ні — players() міг би
        // і не повернути гравця, що вийшов із сервера, але таб мусить
        // показати підсумок до самого RESET.
        for (UUID id : terminal) {
            String name = terminalDisplayNameOf(id);
            if (name == null) continue;
            entries.add(new com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry(
                id, name, RoleSyncPacket.Role.SURVIVOR, "", terminalStateOf(id), 0, 0));
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
            new RoleSyncPacket(RoleSyncPacket.Role.MANIAC, archetype == null ? "" : archetype.id()));
        inventoryAllocation().applyOnJoin(player);
        broadcastRosterAround(player);
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
            new RoleSyncPacket(RoleSyncPacket.Role.SURVIVOR, ""));
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
            new RoleSyncPacket(RoleSyncPacket.Role.SPECTATOR, ""));
        inventoryAllocation().applyOnJoin(player);
        broadcastRosterAround(player);
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
            // Кінематики ще немає — фаза ПРОПУСКАЄТЬСЯ в коді: тут лишається
            // тільки підготовка плану розкидання, і зразу SCATTER. Коли
            // кінематика з'явиться, тут буде `ticksInPhase() >= <тривалість>`,
            // а тривалість повернеться до ConfigSchema (див. коментар там).
            if (!plannerReady) {
                try {
                    prepareSpawnPlan(players);
                } catch (SpawnPlanner.SpawnPlanFailure failure) {
                    reset(players);
                    return;
                }
            }
            if (plannerReady) {
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

        // Дебаг: 0 виживих (або 0 маньяків) НЕ завершує матч — інакше
        // одиночна перевірка за маньяка кидала б у ENDING на першому ж тіку.
        if (!DebugMode.enabled() && noSurvivorsLeft()) {
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

    /**
     * Тривалість CINEMATIC. {@code 0} (дефолт) — фазу пропущено: вона
     * живе рівно один тік, за який {@link MatchStartCoordinator} встигає
     * підготувати план, і одразу йде SCATTER. Так матч стартує моментально
     * з повним розподілом (гравці + генератори + точки луту), поки самої
     * кінематики немає; коли з'явиться — достатньо підняти
     * {@code cinematicSeconds} у maniac.yml, код міняти не треба.
     */
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
            pendingSpawnPlan = SpawnPlanner.SpawnPlan.empty();
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
        // Лок руху, прогрес вставання, правила стаміни — усе за UUID.
        survivors.onPlayerLeft(player);

        List<ServerPlayer> online = player.getServer().getPlayerList().getPlayers();
        if (context.isManiac(player.getUUID())) {
            // Лок ATTACK і перезарядка прив'язані до UUID, а не до списку
            // онлайн-гравців — знімаємо їх ДО можливого reset(...) нижче.
            combat.onPlayerLeft(player);
            // Дебаг: вихід/перезахід не має скидати тобі матч наодинці.
            if (!DebugMode.enabled()
                && !phases.is(GamePhase.LOBBY) && !phases.is(GamePhase.RESET)) reset(online);
            return;
        }

        if (phases.is(GamePhase.LOBBY) || phases.is(GamePhase.RESET)
            || phases.is(GamePhase.ENDING)) return;

        context.removeSurvivor(player.getUUID());
        pendingSpawnPlan = null;
        plannerReady = false;
        scatterApplied = false;
        scatterFailed = phases.is(GamePhase.SCATTER);

        // Дебаг: 0 виживих не завершує матч (див. DebugMode).
        if (!DebugMode.enabled() && context.aliveSurvivorCount() == 0) {
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

    /** Зупинка сервера — знімаємо статичне посилання, щоб не текло. */
    public void shutdown() {
        if (server != null) MatchRuntimeRegistry.cleanup(server);
        if (server != null) MatchBlockRegistry.restoreAll(server);
        server = null;
        Phases.unbind();
    }
}
