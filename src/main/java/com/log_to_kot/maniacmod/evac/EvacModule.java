package com.log_to_kot.maniacmod.evac;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ClearingConfigs;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchObjectives;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.entity.ClimbCarrierEntity;
import com.log_to_kot.maniacmod.entity.EvacRopeEntity;
import com.log_to_kot.maniacmod.entity.GroundItemEntity;
import com.log_to_kot.maniacmod.entity.HelicopterEntity;
import com.log_to_kot.maniacmod.entity.ReceiverEntity;
import com.log_to_kot.maniacmod.loot.GroundItemSpawner;
import com.log_to_kot.maniacmod.map.zones.ClearingZone;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.evac.EscapeAnnouncePacket;
import com.log_to_kot.maniacmod.net.s2c.evac.EvacStatusPacket;
import com.log_to_kot.maniacmod.net.s2c.evac.ReceiverMenuOpenPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ActionBarPacket;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import com.log_to_kot.maniacmod.registry.ModItems;
import com.log_to_kot.maniacmod.spawn.SpawnPoint;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Евакуація після генераторів: приймач → виклик → сигнальна ракета → вертоліт → мотузка →
 * лазіння → відліт.
 *
 * <h3>Місце в архітектурі</h3>
 * <ul>
 *   <li>Модуль — {@link PhaseListener}, стан матчу отримує лише через
 *       {@code Supplier<MatchOrchestrator>} (AI_CODE_GUIDE 2.2); власних «чи йде гра»
 *       прапорців немає — усе від фази.</li>
 *   <li>Фазами НЕ керує сам: ставить {@link MatchObjectives} (HELI_CALLED, FLARE_FIRED,
 *       EXIT_OPENED), а {@code MatchOrchestrator.checkPhaseExitConditions} рухає
 *       POWERED → HELI_WAIT → FINALE → ENDING від цих досягнень. Один шлях переходів.</li>
 *   <li>Усе, що стосується стану евакуації, живе ТУТ і лише тут. Стан скидається входом у
 *       LOBBY/RESET; сутності прибирає {@code MatchRuntimeRegistry} (вони зареєстровані).</li>
 * </ul>
 *
 * <h3>Сервер — єдине джерело правди</h3>
 * Клієнт нічого не вирішує: ні «можна запустити ракету», ні «можна лізти». Кожен C2S-намір
 * (клік по приймачу/мотузці, кнопка виклику) перевіряється заново — фаза, роль, стан,
 * відстань. Клієнтські підказки — лише зручність.
 *
 * <h3>Політ вертольота</h3>
 * Вертоліт НЕ падає з неба над галявиною: він з'являється за {@code heliSpawnDistanceBlocks}
 * (400) блоків від точки зависання й летить до неї прямою. Висота на кожному метрі шляху
 * береться з ПРОФІЛЮ ({@link #buildProfile}): по реальних блоках (дерева, будівлі) уздовж
 * коридору з запасом на розмах гвинта, з обмеженою крутизною. Відліт летить тим самим
 * профілем у зворотний бік — тож і прильот, і відліт гарантовано не зачіпають блоків.
 * Рух рахується в модулі (позиція «віртуальна»), сутність лише слідує за нею, тому далекі
 * чанки не можуть зупинити стадії; коридор тримається підвантаженим квитками чанків.
 *
 * <h3>Відліт</h3>
 * HOVER → ROPE_RETRACT (мотузка підтягується вгору, хапатися вже не можна) → DEPART
 * (розворот і виліт по профілю) → DONE. Якщо виживих більше нема (усі втекли/вибули) —
 * вертоліт відлітає одразу, навіть коли матч уже в ENDING.
 *
 * <h3>Рух гравця по мотузці</h3>
 * Гравець їде пасажиром {@link ClimbCarrierEntity}; сервер двигає носія вгору з
 * {@code climbBlocksPerSecond}. Унаслідок цього:
 * <ul>
 *   <li>удар маньяка без нокауту лазіння НЕ зупиняє (за дизайном; опційна пауза —
 *       {@code climbHitStaggerTicks});</li>
 *   <li>нокаут ({@link #onSurvivorDowned}) скидає гравця: він падає непритомним і лізти не
 *       може, доки його не піднімуть;</li>
 *   <li>добраний до верху — {@code ESCAPED} (мотузка «довела» гравця до вертольота);</li>
 *   <li>Shift (зіскочити) = скасування лазіння, гравець падає з реальної висоти
 *       ({@code fallDistance} виставляється явно: ванільна обнуляється в пасажира).</li>
 * </ul>
 */
public final class EvacModule implements PhaseListener {

    public static final String FLARE_ITEM_ID = "signal_flare";

    /** Як часто (тіки) повторно слати виживим розмітку галявин, поки ракета ще не запущена. */
    private static final int CLEARING_RESYNC_TICKS = 100;

    private static final Random RNG = new Random();

    /** Горизонтальний радіус, у якому ПКМ по мотузці зараховується (мотузка тонка, хітбокс ширший). */
    private static final double ROPE_REACH_HORIZONTAL = 3.0;
    /** Скільки блоків над землею гравець ще «дістає» початок мотузки. */
    private static final double ROPE_REACH_VERTICAL = 3.0;
    /** Горизонтальний крок підтягування носія до осі мотузки, блоків за тік. */
    private static final double CENTERING_STEP = 0.15;
    /** Наскільки збоку від осі мотузки їде гравець: камера не всередині мотузки, мотузка видна перед ним. */
    private static final double CLIMB_SIDE_OFFSET = 0.8;
    /** Скільки блоків ДО верхнього кінця вважається «долізли». */
    private static final double TOP_MARGIN = 1.2;

    // ── Політ ───────────────────────────────────────────────────────────
    /** Крок профілю висот уздовж шляху, блоків. */
    private static final double PROFILE_STEP = 3.0;
    /** Розмах корпусу + гвинта (радіус гвинта 3.5, хвіст 4): блоки в цьому радіусі враховуються. */
    private static final double HELI_FOOTPRINT = 5.0;
    /** Бічні відступи від осі шляху, на яких знімаються висоти (±HELI_FOOTPRINT). */
    private static final double[] LATERAL_OFFSETS = {-5.0, -2.5, 0.0, 2.5, 5.0};
    /** Мін. запас над найвищим блоком біля точки зависання (далі росте до heliCruiseClearance). */
    private static final double HELI_MIN_CLEARANCE = 6.0;
    /** На якій відстані від точки запас виростає до повного. */
    private static final double CLEARANCE_RAMP_BLOCKS = 120.0;
    /** Запас під корпусом над найвищим блоком у радіусі гвинта навколо точки зависання. */
    private static final double OVERHEAD_CLEARANCE = 3.0;
    /** Мотузка не довша за це, навіть якщо поруч дуже високі дерева. */
    private static final double MAX_ROPE_LENGTH = 48.0;
    /** Найкрутіший схил ЗНИЖЕННЯ до галявини (набір над перешкодою — пологіший, з конфігу). */
    private static final double MAX_DESCENT_SLOPE = 0.45;
    /** За скільки секунд (на крейсерській швидкості) вертоліт гальмує перед зависанням. */
    private static final double APPROACH_BRAKE_SECONDS = 3.5;
    /** Мінімальна частка швидкості наприкінці гальмування (інакше не доповз би). */
    private static final double APPROACH_MIN_SPEED_FRACTION = 0.12;
    /** Розгін при відльоті, блоків/с². */
    private static final double DEPART_ACCEL = 9.0;
    /** Швидкість розвороту при відльоті, градусів за тік. */
    private static final float DEPART_TURN_DEG_PER_TICK = 3.0f;
    /** Після цієї відстані від точки відлітаючий вертоліт зникає (далі його вже не видно). */
    private static final double DEPART_DISPOSE_BLOCKS = 300.0;
    /** Скільки ще чекаємо підвантаження коридору після затримки виклику (тіків). */
    private static final int ROUTE_LOAD_EXTRA_TICKS = 200;
    /** Скільки тіків живе квиток чанка; оновлюється кожні TICKET_REFRESH_TICKS. */
    private static final int TICKET_LIFETIME_TICKS = 400;
    private static final int TICKET_REFRESH_TICKS = 100;
    /** Пауза перед відльотом, коли лізти вже нікому (щоб повідомлення про останнього, хто втік, встигли прочитати). */
    private static final int ALL_GONE_DEPART_DELAY_TICKS = 60;
    private static final TicketType<ChunkPos> HELI_TICKET = TicketType.create(
        "maniacmod_heli", Comparator.comparingLong(ChunkPos::toLong), TICKET_LIFETIME_TICKS);
    private static final int RECEIVER_VISUAL_REFRESH_TICKS = 20;
    private static final int FLARE_WATCHDOG_CHECK_TICKS = 20;
    /** Як часто HUD отримує свіжий стан (таймер відльоту йде по секундах). */
    private static final int STATUS_REFRESH_TICKS = 20;
    /** Період «стуку» гвинта. Тимчасово ванільний звук — замінити на власний .ogg. */
    private static final int ROTOR_SOUND_PERIOD_TICKS = 7;

    private final Supplier<MatchOrchestrator> matchSupplier;

    // ── Стан (усе скидається в reset()) ─────────────────────────────────
    private EvacStage stage = EvacStage.DORMANT;
    private long stageTicks = 0;

    private ResourceKey<Level> levelKey;
    private BlockPos receiverPos;
    private UUID receiverId;

    private ClearingZone flareZone;
    private Vec3 flareSpot;
    private int flareBurnLeft;

    private UUID heliId;
    private UUID ropeId;
    /** Вертоліт «в грі» (від появи до відльоту); сутність при цьому може тимчасово бути відсутньою. */
    private boolean heliActive;
    private double hoverY;
    private double groundY;
    private double heliX, heliZ;
    /** Реальна довжина мотузки: від землі до вертольота (може бути більшою за конфіг, якщо над точкою крони). */
    private double ropeLen;
    /** Одиничний вектор від точки зависання до місця появи (напрям, звідки він прилітає). */
    private double dirX, dirZ;
    private double spawnDistance;
    /** Висота польоту на відстанях 0, STEP, 2·STEP… від точки зависання. */
    private double[] profile;
    private final List<ChunkPos> corridor = new ArrayList<>();
    /** Де вертоліт зараз: відстань від точки зависання вздовж шляху (0 = над галявиною). */
    private double progress;
    private double heliCurX, heliCurY, heliCurZ;
    private float heliYaw;
    private double departSpeed;
    /** Скільки мотузки було видно, коли почалось підтягування / скільки видно зараз. */
    private float ropeRetractFrom;
    private float ropeVisible;
    private int allGoneTicks;
    private int hoverTicksLeft;
    private int hoverTicksTotal;
    private int departGraceLeft;

    private final Map<UUID, Climb> climbs = new HashMap<>();
    private int noFlareTicks = 0;
    private long clock = 0;

    private static final class Climb {
        final UUID carrierId;
        int staggerLeft = 0;
        /** Одиничний горизонтальний напрям від осі мотузки до гравця (де він стоїть збоку). */
        final double sideX, sideZ;
        Climb(UUID carrierId, double sideX, double sideZ) {
            this.carrierId = carrierId; this.sideX = sideX; this.sideZ = sideZ;
        }
    }

    public EvacModule(Supplier<MatchOrchestrator> matchSupplier) {
        this.matchSupplier = matchSupplier;
    }

    @Override
    public String id() { return "evac"; }

    private MatchOrchestrator match() { return matchSupplier.get(); }

    // ═════════════════════════════════════════════════════════════════════
    //  Життєвий цикл
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        if (phase == GamePhase.LOBBY || phase == GamePhase.RESET) {
            reset();
        } else if (phase == GamePhase.HUNT) {
            syncClearings(players);
        }
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        // Модуль живе в ігрових фазах (HUNT…FINALE) і ще в ENDING — лише щоб вертоліт, який
        // уже в повітрі (усі втекли — матч іде в ENDING одразу), довершив відліт, а не завис.
        boolean ending = phase == GamePhase.ENDING || phase == GamePhase.VICTORY_SCENE;
        if (!phase.isGameplay() && !ending) return;

        ServerLevel level = level();
        if (level == null) return;

        clock++;
        if (!ending) {
            if (clock % RECEIVER_VISUAL_REFRESH_TICKS == 0) refreshReceiverVisual(phase);

            // Розмітка галявин для жовтого контуру: пакет крихітний (≤ 64 прямокутників),
            // тому раз на 5 с, поки ракета ще актуальна, дешевше й надійніше за відстеження змін.
            if ((stage == EvacStage.DORMANT || stage == EvacStage.WAIT_FLARE)
                && clock % CLEARING_RESYNC_TICKS == 0) {
                syncClearings(players);
            }
        } else {
            wrapUpFlightForEnding(level);
        }

        if ((heliActive || (!ending && stage == EvacStage.DISPATCH)) && clock % TICKET_REFRESH_TICKS == 0) {
            holdRoute(level);
        }

        stageTicks++;
        switch (stage) {
            case DORMANT, WAIT_FLARE -> { if (!ending) tickFlareWatchdog(level, players); }
            case DISPATCH -> { if (!ending) tickDispatch(level); }   // у ENDING нового вертольота не буде
            case INBOUND -> tickInbound(level);
            case DESCEND -> tickDescend(level);
            case ROPE_DROP -> tickRopeDrop(level);
            case HOVER -> tickHover(level);
            case ROPE_RETRACT -> tickRopeRetract(level);
            case DEPART -> tickDepart(level);
            case DONE -> { /* чекаємо ENDING/RESET від оркестратора */ }
        }
        tickFlareBurn(level);
        tickClimbs(level);
        tickRotorSound(level);
        if (stage != EvacStage.DORMANT && clock % STATUS_REFRESH_TICKS == 0) broadcastStatus();
    }

    /**
     * Матч уже в ENDING, а вертоліт ще в повітрі. Нікого лізти — відлітаємо, не чекаючи
     * таймера (це й був баг «усі втекли, а він висить»: раніше модуль у ENDING не тікав
     * узагалі, і вертоліт замирав, поки RESET його не прибирав). Якщо вертоліт ще не
     * з'явився (DORMANT…DISPATCH) — стадію не чіпаємо: він просто не з'явиться.
     */
    private void wrapUpFlightForEnding(ServerLevel level) {
        switch (stage) {
            case INBOUND, DESCEND -> beginFlyOut();
            case ROPE_DROP, HOVER -> beginDeparture(level);
            default -> { }
        }
    }

    private void reset() {
        stage = EvacStage.DORMANT;
        stageTicks = 0;
        levelKey = null;
        receiverPos = null;
        receiverId = null;
        flareZone = null;
        flareSpot = null;
        flareBurnLeft = 0;
        heliId = null;
        ropeId = null;
        heliActive = false;
        profile = null;
        corridor.clear();
        progress = 0;
        departSpeed = 0;
        ropeRetractFrom = 0;
        ropeVisible = 0;
        allGoneTicks = 0;
        hoverTicksLeft = 0;
        hoverTicksTotal = 0;
        departGraceLeft = 0;
        noFlareTicks = 0;
        climbs.clear();
    }

    private void setStage(EvacStage next) {
        stage = next;
        stageTicks = 0;
        broadcastStatus();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Спавн на старті матчу (викликає MatchOrchestrator.applySpawnPlan)
    // ═════════════════════════════════════════════════════════════════════

    /** Ставить приймач у точці, обраній планом. Поворот по X = yaw точки розмітки. */
    public void spawnReceiver(ServerLevel level, SpawnPoint point) {
        if (level == null || point == null) return;
        levelKey = level.dimension();
        receiverPos = point.pos().immutable();
        ReceiverEntity entity = ReceiverEntity.spawn(ModEntityTypes.RECEIVER.get(), level,
            point.pos().getX() + 0.5, point.pos().getY(), point.pos().getZ() + 0.5, point.yaw());
        receiverId = entity.getUUID();
    }

    /**
     * Кладе {@code evacuation.flareCount} сигнальних ракет на РІЗНІ ITEM-точки — з усіх
     * розмічених, не лише «задіяних» луту (ракета не конкурує з бензином за місце).
     * Точок менше, ніж ракет, — стільки, скільки є (і попередження в лог).
     */
    public void spawnFlares(ServerLevel level, List<SpawnPoint> allItemPoints) {
        if (level == null) return;
        int want = ManiacConfigs.get(ConfigSchema.EVAC_FLARE_COUNT);
        List<SpawnPoint> pool = new ArrayList<>(allItemPoints);
        java.util.Collections.shuffle(pool, RNG);
        int placed = 0;
        for (SpawnPoint p : pool) {
            if (placed >= want) break;
            if (GroundItemSpawner.spawnOnMap(level, p.pos(), flareStack(), RNG)) placed++;
        }
        if (placed < want) {
            ManiacMod.LOGGER.warn("[evac] розкладено {} з {} ракет — бракує ITEM-точок", placed, want);
        }
    }

    private static ItemStack flareStack() {
        return new ItemStack(ModItems.get(FLARE_ITEM_ID).get());
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Приймач
    // ═════════════════════════════════════════════════════════════════════

    public boolean ownsReceiverAt(BlockPos pos) {
        return receiverPos != null && receiverPos.equals(pos);
    }

    /** ПКМ по приймачу: відкрити меню (стан кнопки визначає сервер). */
    public void onReceiverInteract(ServerPlayer player, ReceiverEntity receiver) {
        MatchOrchestrator m = match();
        if (!m.isSurvivor(player.getUUID())) return;                    // маньяк і глядачі — ні
        if (!nearReceiver(player)) return;
        ModNetwork.toPlayer(player, new ReceiverMenuOpenPacket(menuState(m.phases().current())));
    }

    /**
     * Кнопка «Викликати вертоліт». Перевіряється ЗАНОВО: меню могло лишитись відкритим,
     * поки стан змінився (інший гравець уже викликав, гравця збили з ніг, він відійшов).
     */
    public void onReceiverCall(ServerPlayer player) {
        MatchOrchestrator m = match();
        if (!m.isSurvivor(player.getUUID())) return;
        if (!standing(player)) { notify(player, ActionBarMessageType.ERROR, "maniacmod.evac.cant_now"); return; }
        if (!nearReceiver(player)) { notify(player, ActionBarMessageType.ERROR, "maniacmod.evac.too_far"); return; }
        if (m.phases().current() != GamePhase.POWERED || stage != EvacStage.DORMANT) {
            notify(player, ActionBarMessageType.ERROR, "maniacmod.evac.cant_call_now");
            return;
        }
        if (ClearingConfigs.complete().isEmpty()) {
            // Без галявин ракету нікуди запускати: не вішаємо команду на нездійсненній вимозі.
            ManiacMod.LOGGER.error("[evac] жодної повної галявини (/maniac clearing) — виклик відхилено");
            notify(player, ActionBarMessageType.ERROR, "maniacmod.evac.no_clearings");
            return;
        }
        setStage(EvacStage.WAIT_FLARE);
        syncClearings(m.onlinePlayers());   // зона з'являється лише тепер, після виклику
        m.completeObjective(MatchObjectives.Objective.HELI_CALLED);   // оркестратор → HELI_WAIT
        broadcast(ActionBarMessageType.SUCCESS, "maniacmod.evac.called", true);
        dispatcherSay(com.log_to_kot.maniacmod.net.s2c.evac.DispatcherPacket.CALL);
    }

    /**
     * Голос диспетчера (звук + текст-субтитри на клієнті). Кому — як і в {@link #broadcast}:
     * виживим і глядачам завжди, маньяку лише якщо ввімкнено EVAC_ANNOUNCE_TO_MANIAC.
     */
    private void dispatcherSay(int script) {
        boolean toManiac = ManiacConfigs.get(ConfigSchema.EVAC_ANNOUNCE_TO_MANIAC);
        var packet = new com.log_to_kot.maniacmod.net.s2c.evac.DispatcherPacket(script);
        for (ServerPlayer p : match().onlinePlayers()) {
            if (match().isManiac(p.getUUID()) && !toManiac) continue;
            ModNetwork.toPlayer(p, packet);
        }
    }

    /** 0 — немає живлення, 1 — готово до виклику, 2 — вже викликано/летить, 3 — недоступно. */
    private int menuState(GamePhase phase) {
        return switch (phase) {
            case HUNT -> 0;
            case POWERED -> stage == EvacStage.DORMANT ? 1 : 2;
            case HELI_WAIT, FINALE -> 2;
            default -> 3;
        };
    }

    private boolean nearReceiver(ServerPlayer player) {
        if (receiverPos == null) return false;
        double max = ManiacConfigs.get(ConfigSchema.EVAC_RECEIVER_USE_DISTANCE);
        double dx = player.getX() - (receiverPos.getX() + 0.5);
        double dy = player.getY() - receiverPos.getY();
        double dz = player.getZ() - (receiverPos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz <= (max + 1.0) * (max + 1.0); // +1: рейкаст і сервер різняться на крок
    }

    private void refreshReceiverVisual(GamePhase phase) {
        ServerLevel level = level();
        if (level == null || receiverId == null) return;
        if (level.getEntity(receiverId) instanceof ReceiverEntity r) {
            r.setVisualStage(switch (phase) {
                case POWERED -> stage == EvacStage.DORMANT ? 1 : 2;
                case HELI_WAIT, FINALE -> stage == EvacStage.DONE ? 3 : 2;
                default -> stage == EvacStage.DONE ? 3 : 0;
            });
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Сигнальна ракета
    // ═════════════════════════════════════════════════════════════════════

    public enum FlareResult { OK, NOT_SURVIVOR, CANT_NOW, NOT_CALLED, NOT_IN_CLEARING, ALREADY_FIRED, IN_AIR }

    /**
     * Спроба запустити ракету. Предмет ({@code SignalFlareItem}) списує стек ЛИШЕ при
     * {@link FlareResult#OK} — невдала спроба не витрачає ракету.
     */
    public FlareResult tryFireFlare(ServerPlayer player) {
        MatchOrchestrator m = match();
        if (!m.isSurvivor(player.getUUID())) return FlareResult.NOT_SURVIVOR;
        if (!standing(player) || climbs.containsKey(player.getUUID())) return FlareResult.CANT_NOW;
        if (stage == EvacStage.DORMANT) return FlareResult.NOT_CALLED;     // спершу виклик з приймача
        if (stage != EvacStage.WAIT_FLARE) return FlareResult.ALREADY_FIRED;
        if (!player.onGround()) return FlareResult.IN_AIR;                 // ракета «лягає на землю»

        ClearingZone zone = clearingAt(player.getX(), player.getZ());
        if (zone == null) return FlareResult.NOT_IN_CLEARING;

        ServerLevel level = player.serverLevel();
        flareZone = zone;
        flareSpot = new Vec3(player.getX(), player.getY(), player.getZ());
        flareBurnLeft = ManiacConfigs.get(ConfigSchema.EVAC_FLARE_BURN_SECONDS) * 20;
        level.playSound(null, player.blockPosition(), SoundEvents.FIREWORK_ROCKET_LAUNCH,
            SoundSource.PLAYERS, 3.0f, 0.8f);

        planRoute(level, zone);
        setStage(EvacStage.DISPATCH);
        m.completeObjective(MatchObjectives.Objective.FLARE_FIRED);
        broadcast(ActionBarMessageType.SUCCESS, "maniacmod.evac.flare_fired", true);
        return FlareResult.OK;
    }

    private ClearingZone clearingAt(double x, double z) {
        for (ClearingZone zone : ClearingConfigs.complete()) {
            if (zone.contains(x, z)) return zone;
        }
        return null;
    }

    /** Партикли ракети: червоний стовп і дим, видимі здалеку (longDistance) — сигнал і ризик. */
    private void tickFlareBurn(ServerLevel level) {
        if (flareBurnLeft <= 0 || flareSpot == null) return;
        flareBurnLeft--;
        if (flareBurnLeft % 2 != 0) return;

        DustParticleOptions red = new DustParticleOptions(new Vector3f(1.0f, 0.12f, 0.08f), 3.0f);
        for (ServerPlayer viewer : level.players()) {
            level.sendParticles(viewer, ParticleTypes.FLAME, true,
                flareSpot.x, flareSpot.y + 0.3, flareSpot.z, 2, 0.15, 0.1, 0.15, 0.01);
            level.sendParticles(viewer, red, true,
                flareSpot.x, flareSpot.y + 1.0, flareSpot.z, 4, 0.5, 0.4, 0.5, 0.0);
            for (int dy = 4; dy <= 32; dy += 4) {
                level.sendParticles(viewer, red, true,
                    flareSpot.x, flareSpot.y + dy, flareSpot.z, 2, 0.8, 1.0, 0.8, 0.0);
            }
            level.sendParticles(viewer, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, true,
                flareSpot.x, flareSpot.y + 1.0, flareSpot.z, 1, 0.1, 0.1, 0.1, 0.03);
        }
    }

    /**
     * Захист від soft-lock. Ракет — {@code flareCount}; якщо жодної не лишилось ніде (всі
     * кинуті в безодню, вибули з власниками) і вертоліт ще не летить, нова з'являється біля
     * приймача. Рахуємо і підлогу, і інвентарі: ракета в кишені живого — не втрачена.
     */
    private void tickFlareWatchdog(ServerLevel level, List<ServerPlayer> players) {
        int limit = ManiacConfigs.get(ConfigSchema.EVAC_FLARE_WATCHDOG_SECONDS) * 20;
        if (limit <= 0 || receiverPos == null) return;
        if (stageTicks % FLARE_WATCHDOG_CHECK_TICKS != 0) return;

        if (flaresInWorld(level, players) > 0) { noFlareTicks = 0; return; }
        noFlareTicks += FLARE_WATCHDOG_CHECK_TICKS;
        if (noFlareTicks < limit) return;

        noFlareTicks = 0;
        if (GroundItemSpawner.spawnOnMap(level, receiverPos.above(0), flareStack(), RNG)) {
            ManiacMod.LOGGER.warn("[evac] ракет не лишилось — видано нову біля приймача");
            broadcast(ActionBarMessageType.INFO, "maniacmod.evac.flare_respawned", false);
        }
    }

    private int flaresInWorld(ServerLevel level, List<ServerPlayer> players) {
        var flare = ModItems.get(FLARE_ITEM_ID).get();
        int count = 0;
        for (var entity : level.getAllEntities()) {
            if (entity instanceof GroundItemEntity g && g.hasStack() && g.stack().is(flare)) count++;
        }
        for (ServerPlayer p : players) {
            if (!match().isSurvivor(p.getUUID())) continue;
            for (ItemStack s : p.getInventory().items) if (s.is(flare)) count++;
        }
        return count;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Маршрут і профіль висот
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Крок 1 (у момент запуску ракети): напрям появи, точка й висота зависання, коридор чанків.
     * Висоти ще НЕ знімаємо — коридор довгий і здебільшого не завантажений: тут лише просимо
     * сервер його підвантажити (квитки), а профіль будуємо в кінці затримки виклику.
     */
    private void planRoute(ServerLevel level, ClearingZone zone) {
        BlockPos heli = zone.heli();
        heliX = heli.getX() + 0.5;
        heliZ = heli.getZ() + 0.5;
        groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, heli.getX(), heli.getZ());

        // Висота зависання: довжина мотузки з конфігу над землею, але не нижче найвищого блоку
        // (крони, дахи, стовбури) у радіусі гвинта навколо точки — інакше корпус і гвинт сиділи б у блоках.
        double wantedHover = groundY + ManiacConfigs.get(ConfigSchema.EVAC_ROPE_LENGTH);
        int footprintTop = Integer.MIN_VALUE;
        for (double ox = -HELI_FOOTPRINT; ox <= HELI_FOOTPRINT; ox += HELI_FOOTPRINT / 2.0) {
            for (double oz = -HELI_FOOTPRINT; oz <= HELI_FOOTPRINT; oz += HELI_FOOTPRINT / 2.0) {
                int x = Mth.floor(heliX + ox), z = Mth.floor(heliZ + oz);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                footprintTop = Math.max(footprintTop, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
            }
        }
        if (footprintTop != Integer.MIN_VALUE) {
            wantedHover = Math.max(wantedHover, footprintTop + OVERHEAD_CLEARANCE);
        }
        hoverY = Math.min(Math.min(wantedHover, groundY + MAX_ROPE_LENGTH), level.getMaxBuildHeight() - 8);
        ropeLen = hoverY - groundY;

        spawnDistance = ManiacConfigs.get(ConfigSchema.EVAC_HELI_SPAWN_DISTANCE);
        pickDirection(level);

        corridor.clear();
        Set<Long> seen = new HashSet<>();
        for (double d = 0; d <= spawnDistance + 16.0; d += 8.0) {
            for (double lat : new double[]{-HELI_FOOTPRINT, 0.0, HELI_FOOTPRINT}) {
                ChunkPos cp = new ChunkPos(Mth.floor(pathX(d, lat)) >> 4, Mth.floor(pathZ(d, lat)) >> 4);
                if (seen.add(cp.toLong())) corridor.add(cp);
            }
        }
        holdRoute(level);
    }

    private double pathX(double d, double lateral) { return heliX + dirX * d - dirZ * lateral; }
    private double pathZ(double d, double lateral) { return heliZ + dirZ * d + dirX * lateral; }

    /**
     * Звідки він прилетить: у бік {@code /maniac heli entry}, якщо адмін її задав; інакше — той
     * із 8 напрямків, над яким у вже завантажених чанках нижчий рельєф (найвільніший захід).
     */
    private void pickDirection(ServerLevel level) {
        BlockPos entry = ClearingConfigs.entry();
        if (entry != null) {
            double dx = entry.getX() + 0.5 - heliX, dz = entry.getZ() + 0.5 - heliZ;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0) { dirX = dx / len; dirZ = dz / len; return; }
        }
        final int candidates = 8;
        double offset = RNG.nextDouble() * Math.PI * 2.0;
        double bestAngle = offset, bestScore = Double.MAX_VALUE;
        for (int k = 0; k < candidates; k++) {
            double a = offset + k * (Math.PI * 2.0 / candidates);
            double score = Double.NEGATIVE_INFINITY;
            for (double d = 8.0; d <= 240.0; d += 8.0) {      // з 8 бл.: дерева біля самої галявини теж рахуються
                int x = Mth.floor(heliX + Math.cos(a) * d), z = Mth.floor(heliZ + Math.sin(a) * d);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                score = Math.max(score, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
            }
            if (score == Double.NEGATIVE_INFINITY) score = groundY + 10.0;   // невідомо — нейтрально
            if (score < bestScore) { bestScore = score; bestAngle = a; }
        }
        dirX = Math.cos(bestAngle);
        dirZ = Math.sin(bestAngle);
    }

    /**
     * Просить сервер тримати коридор завантаженим (квитки чанків із тайм-аутом, нічого не
     * лишається на диску) — без цього далекі чанки вигрузились би, а вертоліт у них зник би.
     * Сам шлях — рівень «завантажений, але не тікає»; околиця галявини — тікає повністю.
     */
    private void holdRoute(ServerLevel level) {
        var chunks = level.getChunkSource();
        for (ChunkPos cp : corridor) chunks.addRegionTicket(HELI_TICKET, cp, 0, cp);
        ChunkPos hover = new ChunkPos(Mth.floor(heliX) >> 4, Mth.floor(heliZ) >> 4);
        chunks.addRegionTicket(HELI_TICKET, hover, 3, hover);
    }

    private boolean corridorLoaded(ServerLevel level) {
        for (ChunkPos cp : corridor) if (!level.hasChunk(cp.x, cp.z)) return false;
        return true;
    }

    /**
     * Крок 2 (кінець затримки): знімає висоти блоків уздовж шляху й будує профіль польоту.
     *
     * <p>Для кожної точки шляху (відстань d від галявини) беремо найвищий блок у смузі шириною
     * ±{@link #HELI_FOOTPRINT} і вимагаємо запас над ним: біля галявини {@link #HELI_MIN_CLEARANCE},
     * далі плавно до {@code heliCruiseClearanceBlocks}. Вимоги «розтягуються» на розмах корпусу й
     * обмежуються по крутизні: {@code P(d) = max_j( R(j) − slope·max(0, |d−j| − footprint) )}
     * (зниження до галявини крутіше за набір висоти — див. коментар у коді).
     * Результат — найнижча висота, що ніде не нижча за вимогу й не крутіша за дозволене, тож
     * зниження до зависання й набір при відльоті йдуть «горкою» над будь-яким рельєфом.</p>
     *
     * <p>Чанки, що так і не завантажились, трактуються найвищим відомим блоком шляху — краще
     * піднятись зайвий раз, ніж зачепити гору.</p>
     */
    private void buildProfile(ServerLevel level) {
        int n = (int) Math.ceil(spawnDistance / PROFILE_STEP) + 1;
        double[] top = new double[n];
        double highest = hoverY;
        int unknown = 0;
        for (int i = 0; i < n; i++) {
            double d = Math.min(i * PROFILE_STEP, spawnDistance);
            double m = Double.NEGATIVE_INFINITY;
            for (double lat : LATERAL_OFFSETS) {
                int x = Mth.floor(pathX(d, lat)), z = Mth.floor(pathZ(d, lat));
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                m = Math.max(m, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
            }
            top[i] = m;
            if (m == Double.NEGATIVE_INFINITY) unknown++; else highest = Math.max(highest, m);
        }
        if (unknown > 0) {
            ManiacMod.LOGGER.warn("[evac] коридор польоту: {} з {} точок у незавантажених чанках — беру найвищий відомий блок", unknown, n);
        }

        double cruiseClear = ManiacConfigs.get(ConfigSchema.EVAC_HELI_CRUISE_CLEARANCE);
        double[] need = new double[n];
        for (int i = 0; i < n; i++) {
            double d = Math.min(i * PROFILE_STEP, spawnDistance);
            double terrain = top[i] == Double.NEGATIVE_INFINITY ? highest : top[i];
            double clearance = HELI_MIN_CLEARANCE
                + (cruiseClear - HELI_MIN_CLEARANCE) * Math.min(1.0, d / CLEARANCE_RAMP_BLOCKS);
            need[i] = Math.max(hoverY, terrain + clearance);
        }

        // Індекс i росте ВІД галявини. Прильот іде з великих i до 0:
        //  • перешкода БЛИЖЧЕ до галявини (j < i): вертоліт має до неї набрати висоту — пологий набір;
        //  • перешкода ДАЛІ від галявини (j > i): після неї він знижується до галявини — крутіше,
        //    інакше біля пагорба вертоліт опинявся б над точкою високо й падав «з неба».
        double speed = ManiacConfigs.get(ConfigSchema.EVAC_HELI_SPEED);
        double vertical = ManiacConfigs.get(ConfigSchema.EVAC_HELI_DESCEND_SPEED);
        double climbSlope = Math.max(0.08, Math.min(0.6, vertical / speed));
        double descentSlope = Math.max(climbSlope, MAX_DESCENT_SLOPE);
        double ceiling = level.getMaxBuildHeight() - 6;

        profile = new double[n];
        for (int i = 0; i < n; i++) {
            double best = need[i];
            for (int j = 0; j < n; j++) {
                double gap = Math.max(0.0, Math.abs(i - j) * PROFILE_STEP - HELI_FOOTPRINT);
                best = Math.max(best, need[j] - (j > i ? descentSlope : climbSlope) * gap);
            }
            profile[i] = Math.min(best, ceiling);
        }
    }

    /** Висота польоту на відстані d від точки зависання (лінійно між вузлами профілю). */
    private double profileY(double d) {
        if (profile == null || profile.length == 0) return hoverY;
        double f = Math.max(0.0, d) / PROFILE_STEP;
        int i = (int) Math.floor(f);
        if (i >= profile.length - 1) return profile[profile.length - 1];
        double t = f - i;
        return profile[i] + (profile[i + 1] - profile[i]) * t;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Політ
    // ═════════════════════════════════════════════════════════════════════

    private void tickDispatch(ServerLevel level) {
        int delay = ManiacConfigs.get(ConfigSchema.EVAC_HELI_DISPATCH_SECONDS) * 20;
        if (stageTicks < delay) return;
        // Коридор довгий: дочікуємось його підвантаження (але не безкінечно), щоб профіль
        // будувався по справжніх блоках, а не по здогадці.
        if (stageTicks < delay + ROUTE_LOAD_EXTRA_TICKS && !corridorLoaded(level)) return;

        buildProfile(level);
        progress = spawnDistance;
        double sx = pathX(spawnDistance, 0), sz = pathZ(spawnDistance, 0);
        heliActive = true;
        moveHeli(level, sx, profileY(progress), sz, yawToward(sx, sz, heliX, heliZ));
        setStage(EvacStage.INBOUND);
        broadcast(ActionBarMessageType.INFO, "maniacmod.evac.heli_inbound", true);
    }

    /**
     * Прильот: іде вздовж шляху до галявини, висота — з профілю, наприкінці плавно гальмує.
     * Жодних вертикальних «падінь з неба»: найкрутіше, що тут буває, — пологий схил профілю.
     */
    private void tickInbound(ServerLevel level) {
        double speed = ManiacConfigs.get(ConfigSchema.EVAC_HELI_SPEED) / 20.0;       // блоків/тік
        double brake = Math.max(30.0, speed * 20.0 * APPROACH_BRAKE_SECONDS);
        double v = Math.max(speed * APPROACH_MIN_SPEED_FRACTION, speed * Math.min(1.0, progress / brake));
        progress -= v;
        if (progress <= 0.0) {
            progress = 0.0;
            moveHeli(level, heliX, profileY(0.0), heliZ, heliYaw);
            setStage(EvacStage.DESCEND);
            return;
        }
        moveHeli(level, pathX(progress, 0), profileY(progress), pathZ(progress, 0), heliYaw);
    }

    /**
     * Над галявиною. Зазвичай профіль уже закінчився на висоті зависання й спуску нема;
     * якщо навколо були високі дерева й профіль закінчився вище — доспускаємось прямо
     * над галявиною (стовп над точкою перевірено в {@link #planRoute}).
     */
    private void tickDescend(ServerLevel level) {
        double y = heliCurY;
        if (y > hoverY + 1.0E-6) {
            double step = ManiacConfigs.get(ConfigSchema.EVAC_HELI_DESCEND_SPEED) / 20.0;
            double remaining = y - hoverY;
            y = Math.max(hoverY, y - Math.max(0.04, Math.min(step, remaining * 0.12)));
            moveHeli(level, heliX, y, heliZ, heliYaw);
            if (y > hoverY + 1.0E-6) return;
        }
        EvacRopeEntity rope = EvacRopeEntity.spawn(ModEntityTypes.EVAC_ROPE.get(), level,
            heliX, groundY, heliZ, (float) ropeLen);
        ropeId = rope.getUUID();
        ropeVisible = 0f;
        setStage(EvacStage.ROPE_DROP);
        broadcast(ActionBarMessageType.INFO, "maniacmod.evac.rope_dropping", true);
    }

    private void tickRopeDrop(ServerLevel level) {
        EvacRopeEntity rope = ensureRope(level);
        float total = (float) ropeLen;
        float seconds = ManiacConfigs.get(ConfigSchema.EVAC_ROPE_DROP_SECONDS).floatValue();
        ropeVisible = total * Math.min(1f, stageTicks / Math.max(1f, seconds * 20f));
        if (rope == null) return;                      // чанк не завантажений — стан збережено, продовжимо
        rope.setVisibleLength(ropeVisible);
        if (rope.isFullyDropped()) {
            hoverTicksLeft = ManiacConfigs.get(ConfigSchema.EVAC_HELI_STAY_SECONDS) * 20;
            hoverTicksTotal = hoverTicksLeft;
            allGoneTicks = 0;
            setStage(EvacStage.HOVER);
            match().completeObjective(MatchObjectives.Objective.EXIT_OPENED);   // оркестратор → FINALE
            broadcast(ActionBarMessageType.SUCCESS, "maniacmod.evac.rope_ready", true);
        }
    }

    private void tickHover(ServerLevel level) {
        ensureRope(level);
        ensureHeli(level);

        // Лізти більше нікому (усі втекли, вибули чи лежать без сил): чекати таймера нема сенсу.
        // Без цього вертоліт у дебаг-матчі (де 0 виживих не завершує гру) висів би всі 90 с.
        if (climbs.isEmpty() && nobodyCanClimb()) {
            if (++allGoneTicks >= ALL_GONE_DEPART_DELAY_TICKS) { beginDeparture(level); return; }
        } else {
            allGoneTicks = 0;
        }

        if (hoverTicksLeft > 0) {
            hoverTicksLeft--;
            // Попередження для тих, хто ще не поліз: за 30 і 10 секунд.
            if (hoverTicksLeft == 600) broadcast(ActionBarMessageType.INFO, "maniacmod.evac.leaving_30", true);
            if (hoverTicksLeft == 200) broadcast(ActionBarMessageType.ERROR, "maniacmod.evac.leaving_10", true);
            return;
        }
        // Час вийшов: чекаємо тих, хто ВЖЕ лізе, але не безкінечно — інакше один
        // «вічно лізучий» тримав би вертоліт (і матч) у заручниках.
        if (!climbs.isEmpty()) {
            if (departGraceLeft == 0) {
                departGraceLeft = ManiacConfigs.get(ConfigSchema.EVAC_DEPART_GRACE_SECONDS) * 20;
                if (departGraceLeft > 0) return;
            } else if (--departGraceLeft > 0) {
                return;
            }
        }
        beginDeparture(level);
    }

    /** Чи є хтось онлайн, хто стоїть на ногах і міг би ще дійти до мотузки. */
    private boolean nobodyCanClimb() {
        for (UUID id : match().survivorIds()) {
            if (match().onlinePlayer(id) == null) continue;
            SurvivorState s = match().survivorStateOf(id);
            if (s == SurvivorState.HEALTHY || s == SurvivorState.BROKEN_LEG) return false;
        }
        return true;
    }

    /**
     * Початок відльоту. Спершу мотузку ПІДТЯГУЮТЬ угору ({@link EvacStage#ROPE_RETRACT}) і лише
     * потім вертоліт летить. Уже з цього моменту хапатися не можна: сервер відхиляє клік
     * ({@link #onRopeInteract}), а мотузка перестає бути pickable ще в цьому ж тіку.
     * Хто ще на мотузці — падає: вертоліт не чекає.
     */
    private void beginDeparture(ServerLevel level) {
        for (UUID id : new ArrayList<>(climbs.keySet())) {
            ServerPlayer p = match().onlinePlayer(id);
            if (p != null) notify(p, ActionBarMessageType.ERROR, "maniacmod.evac.left_behind");
            release(level, id, true);
        }
        EvacRopeEntity rope = rope(level);
        ropeRetractFrom = rope != null ? rope.visibleLength() : ropeVisible;
        ropeVisible = ropeRetractFrom;
        // Одразу трохи вкорочуємо: isFullyDropped() стає false, мотузку вже не можна взяти в приціл.
        if (rope != null) rope.setVisibleLength(Math.max(0f, ropeRetractFrom - 0.05f));
        setStage(EvacStage.ROPE_RETRACT);
        broadcast(ActionBarMessageType.ERROR, "maniacmod.evac.departing", true);
    }

    private void tickRopeRetract(ServerLevel level) {
        EvacRopeEntity rope = ensureRope(level);
        float seconds = ManiacConfigs.get(ConfigSchema.EVAC_ROPE_DROP_SECONDS).floatValue();
        float t = Math.min(1f, stageTicks / Math.max(1f, seconds * 20f));
        float eased = t * t * (3f - 2f * t);                      // плавний старт і зупинка лебідки
        ropeVisible = ropeRetractFrom * (1f - eased);
        if (rope != null) rope.setVisibleLength(ropeVisible);
        if (t < 1f) return;
        if (rope != null) rope.discard();
        ropeId = null;
        beginFlyOut();
    }

    private void beginFlyOut() {
        departSpeed = 0.0;
        setStage(EvacStage.DEPART);
    }

    /**
     * Відліт: розворот на місці й виліт ТИМ САМИМ профілем, яким він прилетів (у зворотний бік).
     * Профіль безпечний у обидва боки, тож відліт так само не зачіпає блоків; повільний
     * розгін не дає «вистрілити» з місця.
     */
    private void tickDepart(ServerLevel level) {
        float outYaw = yawToward(heliX, heliZ, pathX(spawnDistance, 0), pathZ(spawnDistance, 0));
        heliYaw = approachYaw(heliYaw, outYaw, DEPART_TURN_DEG_PER_TICK);

        // Профіль міг закінчитись вище за висоту зависання (високі дерева поруч): спершу
        // підіймаємось над галявиною, і лише потім рушаємо.
        double wantY = profileY(progress);
        if (progress <= 0.0 && heliCurY < wantY - 1.0E-3) {
            double rise = Math.max(0.05, ManiacConfigs.get(ConfigSchema.EVAC_HELI_DESCEND_SPEED) / 20.0);
            moveHeli(level, heliCurX, Math.min(wantY, heliCurY + rise), heliCurZ, heliYaw);
            return;
        }

        double maxSpeed = ManiacConfigs.get(ConfigSchema.EVAC_HELI_SPEED) / 20.0;
        departSpeed = Math.min(maxSpeed, departSpeed + DEPART_ACCEL / 400.0);
        progress += departSpeed;
        if (progress >= Math.min(spawnDistance, DEPART_DISPOSE_BLOCKS)) {
            HelicopterEntity heli = heli(level);
            if (heli != null) heli.discard();
            heliId = null;
            heliActive = false;
            setStage(EvacStage.DONE);
            return;
        }
        moveHeli(level, pathX(progress, 0), profileY(progress), pathZ(progress, 0), heliYaw);
    }

    /** true, коли евакуація відпрацювала повністю: оркестратор тоді завершує матч (ENDING). */
    public boolean isFinished() { return stage == EvacStage.DONE; }

    /** Іде відліт (мотузка підтягується або вертоліт ще в кадрі): оркестратор не скидає матч. */
    public boolean isDeparting() { return stage == EvacStage.ROPE_RETRACT || stage == EvacStage.DEPART; }

    // ── Сутності: позиція «віртуальна», сутність лише слідує ──────────────

    /** Ставить вертоліт у точку. Позиція зберігається в модулі, тож стадії не залежать від чанків. */
    private void moveHeli(ServerLevel level, double x, double y, double z, float yaw) {
        heliCurX = x; heliCurY = y; heliCurZ = z; heliYaw = yaw;
        HelicopterEntity heli = ensureHeli(level);
        if (heli == null || !level.hasChunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4)) return;
        heli.setPos(x, y, z);
        heli.setYRot(yaw);
    }

    /**
     * Сутність вертольота: існуюча, або створена заново (вигрузка чанка, стороння команда) — але лише
     * там, де чанк завантажений: додати сутність у незавантажений чанк означало б загубити її.
     */
    private HelicopterEntity ensureHeli(ServerLevel level) {
        if (!heliActive) return null;
        HelicopterEntity heli = heli(level);
        if (heli != null) return heli;
        if (!level.hasChunk(Mth.floor(heliCurX) >> 4, Mth.floor(heliCurZ) >> 4)) return null;
        if (heliId != null) ManiacMod.LOGGER.warn("[evac] сутність вертольота зникла — створено заново");
        heli = HelicopterEntity.spawn(ModEntityTypes.HELICOPTER.get(), level, heliCurX, heliCurY, heliCurZ, heliYaw);
        heliId = heli.getUUID();
        return heli;
    }

    /** Те саме для мотузки: відновлюється з тією ж видимою довжиною. */
    private EvacRopeEntity ensureRope(ServerLevel level) {
        EvacRopeEntity rope = rope(level);
        if (rope != null || ropeId == null) return rope;
        if (!level.hasChunk(Mth.floor(heliX) >> 4, Mth.floor(heliZ) >> 4)) return null;
        ManiacMod.LOGGER.warn("[evac] сутність мотузки зникла — створено заново");
        rope = EvacRopeEntity.spawn(ModEntityTypes.EVAC_ROPE.get(), level, heliX, groundY, heliZ, (float) ropeLen);
        rope.setVisibleLength(ropeVisible);
        ropeId = rope.getUUID();
        return rope;
    }

    private static float approachYaw(float current, float target, float maxStep) {
        float diff = Mth.wrapDegrees(target - current);
        if (Math.abs(diff) <= maxStep) return target;
        return current + Math.signum(diff) * maxStep;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Мотузка й лазіння
    // ═════════════════════════════════════════════════════════════════════

    /** ПКМ по мотузці. */
    public void onRopeInteract(ServerPlayer player, EvacRopeEntity rope) {
        MatchOrchestrator m = match();
        UUID id = player.getUUID();
        if (!m.isSurvivor(id)) return;
        if (stage != EvacStage.HOVER) {
            if (stage == EvacStage.ROPE_RETRACT || stage == EvacStage.DEPART || stage == EvacStage.DONE) {
                notify(player, ActionBarMessageType.ERROR, "maniacmod.evac.too_late");
            }
            return;
        }
        if (climbs.containsKey(id)) return;
        if (!canClimb(player)) {
            notify(player, ActionBarMessageType.ERROR, "maniacmod.evac.cant_climb");
            return;
        }
        double dx = player.getX() - rope.getX(), dz = player.getZ() - rope.getZ();
        if (dx * dx + dz * dz > ROPE_REACH_HORIZONTAL * ROPE_REACH_HORIZONTAL
            || player.getY() > groundY + ROPE_REACH_VERTICAL) {
            notify(player, ActionBarMessageType.ERROR, "maniacmod.evac.too_far");
            return;
        }

        ServerLevel level = player.serverLevel();
        // Сторона, з якої гравець підійшов: там він і лізе. Якщо стоїть рівно на осі — позаду себе.
        double sx = player.getX() - rope.getX(), sz = player.getZ() - rope.getZ();
        double sd = Math.sqrt(sx * sx + sz * sz);
        if (sd < 0.05) {
            double yaw = Math.toRadians(player.getYRot());
            sx = Math.sin(yaw); sz = -Math.cos(yaw);
        } else {
            sx /= sd; sz /= sd;
        }
        double startX = heliX + sx * CLIMB_SIDE_OFFSET, startZ = heliZ + sz * CLIMB_SIDE_OFFSET;
        ClimbCarrierEntity carrier = ClimbCarrierEntity.spawn(ModEntityTypes.CLIMB_CARRIER.get(), level,
            startX, player.getY(), startZ);
        if (carrier == null) return;
        player.setDeltaMovement(Vec3.ZERO);
        player.setSprinting(false);
        // Обличчям до мотузки (поворот шлеться клієнту до того, як гравець сяде на носія).
        // Трохи повернутий убік (−30°), щоб мотузка була збоку у полі зору, а не рівно по центру екрана.
        float faceYaw = (float) Math.toDegrees(Math.atan2(sx, -sz)) - 30f;
        player.connection.teleport(startX, player.getY(), startZ, faceYaw, 0f);
        if (!player.startRiding(carrier, true)) { carrier.discard(); return; }
        climbs.put(id, new Climb(carrier.getUUID(), sx, sz));
        notify(player, ActionBarMessageType.INFO, "maniacmod.evac.climbing");
    }

    /** Лізти може лише той, хто на ногах (HEALTHY/BROKEN_LEG), не в пастці. */
    private boolean canClimb(ServerPlayer player) {
        if (!standing(player)) return false;
        return !match().traps().isTrapped(player.getUUID());
    }

    private boolean standing(ServerPlayer player) {
        SurvivorState s = match().survivorStateOf(player.getUUID());
        return s == SurvivorState.HEALTHY || s == SurvivorState.BROKEN_LEG;
    }

    public boolean isClimbing(UUID playerId) { return climbs.containsKey(playerId); }

    private void tickClimbs(ServerLevel level) {
        if (climbs.isEmpty()) return;
        double perTick = ManiacConfigs.get(ConfigSchema.EVAC_CLIMB_SPEED) / 20.0;
        double topY = hoverY - TOP_MARGIN;

        for (UUID id : new ArrayList<>(climbs.keySet())) {
            Climb climb = climbs.get(id);
            ServerPlayer player = match().onlinePlayer(id);
            if (player == null || !(level.getEntity(climb.carrierId) instanceof ClimbCarrierEntity carrier)
                || carrier.isRemoved()) {
                release(level, id, false);
                continue;
            }
            // Збили з ніг, потрапив у пастку, вибув — лазіння скасовано (падає з висоти).
            if (!canClimb(player)) { release(level, id, true); continue; }
            // Зіскочив сам (Shift) або щось його скинуло — це скасування, не «лізе далі»:
            // повернути силоміць означало б відібрати в гравця вихід.
            if (player.getVehicle() != carrier) { release(level, id, true); continue; }

            if (climb.staggerLeft > 0) { climb.staggerLeft--; continue; }

            double x = carrier.getX(), z = carrier.getZ();
            // Ціль — точка збоку від осі мотузки, а не сама вісь.
            double cx = heliX + climb.sideX * CLIMB_SIDE_OFFSET - x;
            double cz = heliZ + climb.sideZ * CLIMB_SIDE_OFFSET - z;
            double cd = Math.sqrt(cx * cx + cz * cz);
            if (cd > 1.0E-4) {
                double step = Math.min(CENTERING_STEP, cd);
                x += cx / cd * step;
                z += cz / cd * step;
            }
            double y = carrier.getY() + perTick;
            carrier.setPos(x, y, z);

            if (y >= topY) escape(level, player, carrier);
        }
    }

    /**
     * Адмінське «втік зараз» ({@code /maniac win survivors}): те саме, що {@link #escape}, але без
     * носія — гравця знімають із мотузки (якщо ліз), фіксують втеклим, роблять глядачем і
     * оголошують усім («Ім'я втік! 3/6» — той самий тост, що й при справжній втечі).
     * Якщо вертоліт саме висить над мотузкою — глядач-камера зависає «всередині» нього.
     */
    public void forceEscape(ServerPlayer player) {
        ServerLevel level = level();
        UUID id = player.getUUID();
        if (level != null) release(level, id, false);

        var survivors = match().survivors();
        survivors.onSurvivorLeftMatch(player, SurvivorState.ESCAPED);
        match().markEscaped(player);
        survivors.becomeSpectatorAfterEscape(player);
        if (level != null && stage == EvacStage.HOVER && player.serverLevel() == level) {
            player.teleportTo(level, heliX, hoverY + 1.0, heliZ, player.getYRot(), player.getXRot());
        }
        notify(player, ActionBarMessageType.SUCCESS, "maniacmod.evac.escaped");
        announceEscape(player);
    }

    /** Скасувати лазіння всіх (без падіння): перед сценою перемоги маніяка, щоб ніхто не висів на носії. */
    public void cancelClimbs() {
        ServerLevel level = level();
        if (level == null) { climbs.clear(); return; }
        for (UUID id : new ArrayList<>(climbs.keySet())) release(level, id, false);
    }

    /** Долізла: гравець вибрався. */
    private void escape(ServerLevel level, ServerPlayer player, ClimbCarrierEntity carrier) {
        UUID id = player.getUUID();
        climbs.remove(id);
        player.stopRiding();
        carrier.discard();

        var survivors = match().survivors();
        survivors.onSurvivorLeftMatch(player, SurvivorState.ESCAPED);
        match().markEscaped(player);
        survivors.becomeSpectatorAfterEscape(player);
        // «Всередині» вертольота: глядач-камера над мотузкою, а не на землі під ним.
        player.teleportTo(level, heliX, hoverY + 1.0, heliZ, player.getYRot(), player.getXRot());
        notify(player, ActionBarMessageType.SUCCESS, "maniacmod.evac.escaped");
        announceEscape(player);
        com.log_to_kot.maniacmod.server.ServerHooks.broadcastRoster(match().onlinePlayers());
    }

    /**
     * «Ім'я втік! 3/6» — ВСІМ (і маньяку: лічильник є частиною напруги). Лічильник береться з
     * оркестратора ПІСЛЯ markEscaped, тож поточний гравець уже врахований; «усього» = втекли +
     * вибули + ще в грі, тобто скільки виживих стартувало (вихід із сервера зменшує його).
     */
    private void announceEscape(ServerPlayer escapee) {
        MatchOrchestrator m = match();
        var packet = new EscapeAnnouncePacket(escapee.getGameProfile().getName(),
            m.escapedSurvivorIds().size(), m.survivorTotal());
        for (ServerPlayer p : m.onlinePlayers()) ModNetwork.toPlayer(p, packet);
        broadcastStatus();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Стан для HUD і звук
    // ═════════════════════════════════════════════════════════════════════

    /** Грубий крок для гравця (див. {@link EvacStatusPacket.Step}). */
    private int hudStep() {
        return switch (stage) {
            case DORMANT -> EvacStatusPacket.Step.IDLE;
            case WAIT_FLARE -> EvacStatusPacket.Step.CALLED;
            case DISPATCH, INBOUND, DESCEND -> EvacStatusPacket.Step.INBOUND;
            case ROPE_DROP -> EvacStatusPacket.Step.ROPE_DROP;
            case HOVER -> EvacStatusPacket.Step.CLIMB;
            case ROPE_RETRACT, DEPART, DONE -> EvacStatusPacket.Step.DEPARTING;
        };
    }

    private EvacStatusPacket statusPacket() {
        MatchOrchestrator m = match();
        int step = hudStep();
        int left = -1, total = 0;
        if (step == EvacStatusPacket.Step.CLIMB) {
            // Основний час, потім — пільговий для тих, хто вже лізе.
            int ticks = hoverTicksLeft > 0 ? hoverTicksLeft : Math.max(0, departGraceLeft);
            left = (ticks + 19) / 20;
            total = (hoverTicksTotal + 19) / 20;
        }
        return new EvacStatusPacket(step, m.escapedSurvivorIds().size(), m.survivorTotal(), left, total);
    }

    /** Усім онлайн-гравцям (і глядачам: вони теж стежать за втечею). */
    private void broadcastStatus() {
        var packet = statusPacket();
        for (ServerPlayer p : match().onlinePlayers()) ModNetwork.toPlayer(p, packet);
    }

    /** Тому, хто зайшов посеред матчу (див. {@code ServerHooks}). */
    public void syncStatusTo(ServerPlayer player) {
        ModNetwork.toPlayer(player, statusPacket());
    }

    /**
     * Глухий стукіт гвинта, поки вертоліт у повітрі. ТИМЧАСОВО: ванільний звук серцебиття
     * Вардена на низькому тоні дає схожий «чоп-чоп»; власний запис потрібно підкласти в
     * {@code ModSounds} і замінити виклик тут.
     */
    private void tickRotorSound(ServerLevel level) {
        if (!heliActive || stage == EvacStage.DISPATCH || stage == EvacStage.DONE) return;
        if (clock % ROTOR_SOUND_PERIOD_TICKS != 0) return;
        float vol = stage == EvacStage.DEPART ? 5f : 7f;
        level.playSound(null, heliCurX, heliCurY, heliCurZ,
            SoundEvents.WARDEN_HEARTBEAT, SoundSource.NEUTRAL, vol, 0.55f);
    }

    /**
     * Знімає гравця з мотузки.
     *
     * @param fall true — гравець падає: {@code fallDistance} виставляється з реальної висоти
     *             (у пасажира ванільна обнулялась би, і падіння з 12 блоків «нічого не
     *             коштувало» б). Далі падіння вирішує {@code SurvivorModule.onFall}.
     */
    private void release(ServerLevel level, UUID id, boolean fall) {
        Climb climb = climbs.remove(id);
        if (climb == null) return;
        ServerPlayer player = match().onlinePlayer(id);
        var carrierEntity = level.getEntity(climb.carrierId);
        double height = carrierEntity != null ? carrierEntity.getY() - groundY : 0;
        if (player != null && player.getVehicle() != null && player.getVehicle().getUUID().equals(climb.carrierId)) {
            player.stopRiding();
        }
        if (player != null && fall && height > 0) {
            player.fallDistance = (float) height;
            player.setDeltaMovement(player.getDeltaMovement().x, 0, player.getDeltaMovement().z);
            player.hurtMarked = true;
        }
        if (carrierEntity != null) carrierEntity.discard();
    }

    // ── Хуки з інших модулів (викликаються фасадами/ SurvivorModule) ──────

    /** Гравця збили до 0 хп. Викликається ПІСЛЯ встановлення UNCONSCIOUS — падіння безболісне. */
    public void onSurvivorDowned(ServerPlayer player) {
        ServerLevel level = level();
        if (level != null && climbs.containsKey(player.getUUID())) release(level, player.getUUID(), true);
    }

    /** Влучання, яке НЕ збило з ніг. За дизайном лазіння не зупиняє; пауза — ручка балансу. */
    public void onClimberHit(ServerPlayer victim) {
        Climb climb = climbs.get(victim.getUUID());
        if (climb == null) return;
        climb.staggerLeft = ManiacConfigs.get(ConfigSchema.EVAC_CLIMB_HIT_STAGGER_TICKS);
    }

    public void onPlayerLeft(ServerPlayer player) {
        ServerLevel level = level();
        if (level != null) release(level, player.getUUID(), false);
    }

    // ── Володіння сутностями (сироти самознищуються) ──────────────────────

    public boolean ownsHeli(HelicopterEntity e)      { return heliId != null && heliId.equals(e.getUUID()); }
    public boolean ownsRope(EvacRopeEntity e)        { return ropeId != null && ropeId.equals(e.getUUID()); }
    public boolean ownsCarrier(ClimbCarrierEntity e) {
        for (Climb c : climbs.values()) if (c.carrierId.equals(e.getUUID())) return true;
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Допоміжне
    // ═════════════════════════════════════════════════════════════════════

    private ServerLevel level() {
        var server = match().server();
        return server == null || levelKey == null ? null : server.getLevel(levelKey);
    }

    private HelicopterEntity heli(ServerLevel level) {
        return heliId != null && level.getEntity(heliId) instanceof HelicopterEntity h ? h : null;
    }

    private EvacRopeEntity rope(ServerLevel level) {
        return ropeId != null && level.getEntity(ropeId) instanceof EvacRopeEntity r ? r : null;
    }

    /** Yaw так, щоб ніс дивився на ціль (Minecraft: yaw 0 = +Z, зростає за годинниковою). */
    private static float yawToward(double fromX, double fromZ, double toX, double toZ) {
        return (float) (Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ)));
    }

    /**
     * Що клієнту дозволено знати про галявини ЗАРАЗ. Поки вертоліт не викликано (DORMANT) —
     * нічого: жовта зона не має світитись на землі до виклику з приймача. Після виклику
     * (WAIT_FLARE і далі) — повна розмітка.
     */
    private List<ClearingZone> visibleClearings() {
        return stage == EvacStage.DORMANT ? List.of() : ClearingConfigs.complete();
    }

    /** Розсилає розмітку галявин виживим (для жовтого контуру); до виклику вертольота — порожню. */
    public void syncClearings(List<ServerPlayer> players) {
        var packet = com.log_to_kot.maniacmod.net.s2c.evac.ClearingSyncPacket.of(visibleClearings());
        for (ServerPlayer p : players) {
            if (match().isSurvivor(p.getUUID())) ModNetwork.toPlayer(p, packet);
        }
    }

    public void syncClearingsTo(ServerPlayer player) {
        ModNetwork.toPlayer(player,
            com.log_to_kot.maniacmod.net.s2c.evac.ClearingSyncPacket.of(visibleClearings()));
    }

    private void notify(ServerPlayer player, ActionBarMessageType type, String key) {
        ModNetwork.toPlayer(player, new ActionBarPacket(type, key));
    }

    private void broadcast(ActionBarMessageType type, String key, boolean maniacToo) {
        boolean toManiac = maniacToo && ManiacConfigs.get(ConfigSchema.EVAC_ANNOUNCE_TO_MANIAC);
        for (ServerPlayer p : match().onlinePlayers()) {
            boolean isManiac = match().isManiac(p.getUUID());
            if (isManiac ? toManiac : true) notify(p, type, key);
        }
    }
}
