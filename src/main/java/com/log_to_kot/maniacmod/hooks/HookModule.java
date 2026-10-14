package com.log_to_kot.maniacmod.hooks;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.entity.HookEntity;
import com.log_to_kot.maniacmod.entity.VictimSeatEntity;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.hooks.HookHighlightPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ActionBarPacket;
import com.log_to_kot.maniacmod.offline.ManiacOffline;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import com.log_to_kot.maniacmod.spawn.SpawnPoint;
import com.log_to_kot.maniacmod.spawn.SpawnPointKind;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Гаки (Dead by Daylight): лежить → маньяк несе на плечі → висить на гаку → помирає за таймером
 * або його знімають союзники.
 *
 * <h3>Що тут, а що ні</h3>
 * Стан «непритомний» ({@code SurvivorState.UNCONSCIOUS}), хп, підняття ПКМ×8 с і розсип предметів
 * при смерті ЛИШАЮТЬСЯ в {@code SurvivorModule} — це той самий життєвий цикл, що й раніше. Цей модуль
 * додає до нього ТРИ під-стани ({@link HookStatus}) і владу над таймером до смерті:
 * <pre>
 *   LYING    лежить. Не вмирає (hooks.lyingBleedsOut=false) — чекає на союзника або на маньяка.
 *   CARRIED  на плечі маньяка. Таймер стоїть. НЕ світиться (контур гасне на плечі).
 *   HOOKED   на гаку. Таймер hooks.hangSeconds; стоїть, поки маньяк ближче hooks.maniacPauseRadiusBlocks.
 * </pre>
 * {@code SurvivorModule} питає {@link #managesTimer} і для таких не веде свій відлік; натомість
 * цей модуль веде таймер висіння, пише залишок у {@code SurvivorModule#setDownedTicksLeft} (звідти
 * його бере пакет усім клієнтам) і сам вбиває через {@code SurvivorModule#expireDowned}.
 *
 * <h3>Як гравець «сидить»</h3>
 * Невидима {@link VictimSeatEntity}: гравець — її пасажир, сервер щотіка ставить їй позицію за
 * формулою (на плечі — від тіла маньяка, на гаку — від гака). Позиція жертви на гаку — три
 * числа в {@code hooks.yml} (вперед/убік/вгору від гака) + поворот; змінив файл — жертва
 * переставляється на льоту, без перезапуску.
 *
 * <h3>Підсвітка</h3>
 * <ul>
 *   <li>Усім: контур жертви КОЛЬОРОМ СТАНУ (лежить — золотий, висить — червоний). На плечі
 *       маньяка контуру НЕМАЄ — він повертається, коли жертву кинули на землю або повісили.
 *       Серверний {@code setGlowingTag} + команда за кольором — його бачать усі.</li>
 *   <li>Лише маньяку, поки він несе: вільні гаки (див. {@link HookHighlightPacket}).</li>
 * </ul>
 *
 * <h3>Чого немає (свідомо)</h3>
 * Лежачі офлайн-тіла (гравець вийшов) в механіці гаків не беруть участі: їх не підняти на плече,
 * вони вмирають за звичайним таймером кровотечі. Це окреме рішення дизайну — перепитати.
 */
public final class HookModule implements PhaseListener {

    private static final String TEAM_PREFIX = "maniac_hook_";
    private static final String LEGACY_DOWNED_TEAM = "maniac_survivor_downed";

    private final Supplier<MatchOrchestrator> matchSupplier;

    /** Усі гаки цього матчу (UUID сутності). Сутність, якої тут немає, — сирота й сама зникає. */
    private final Set<UUID> hookIds = new HashSet<>();

    /** жертва → як її несуть. */
    private final Map<UUID, Carry> carried = new HashMap<>();
    /** жертва → як вона висить. */
    private final Map<UUID, Hang> hangs = new HashMap<>();
    /** гак → жертва, що на ньому висить. */
    private final Map<UUID, UUID> hookVictim = new HashMap<>();
    /** жертва → до якого тіку гри її не можна знову підняти (після падіння з плеча). */
    private final Map<UUID, Long> repickupBlockedUntil = new HashMap<>();

    /** Таймер висіння стоїть саме зараз (маньяк поруч). Перебудовується щотіка. */
    private final Set<UUID> timerPaused = new HashSet<>();

    private long tickCounter = 0;

    private record Carry(UUID maniacId, UUID seatId) {}

    private static final class Hang {
        final UUID hookId;
        final UUID seatId;
        int ticksLeft;

        Hang(UUID hookId, UUID seatId, int ticksLeft) {
            this.hookId = hookId;
            this.seatId = seatId;
            this.ticksLeft = ticksLeft;
        }
    }

    public HookModule(Supplier<MatchOrchestrator> matchSupplier) {
        this.matchSupplier = matchSupplier;
    }

    private MatchOrchestrator match() {
        return matchSupplier.get();
    }

    @Override
    public String id() {
        return "hooks";
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Життєвий цикл
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        // Новий матч (або вихід у лобі) не успадковує жертв, гаки й команди попереднього.
        if (phase == GamePhase.LOBBY || phase == GamePhase.RESET) {
            clearAll(players);
        } else if (phase == GamePhase.ENDING || phase == GamePhase.VICTORY_SCENE) {
            // Підсумки: нікого не лишаємо на плечі/гаку. НЕ onPhaseExit — перехід HUNT→POWERED
            // теж «вихід із ігрової фази», а жертви мають пережити зміну фази.
            releaseEveryone(players);
        }
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        if (!phase.isGameplay()) return;
        tickCounter++;

        ServerPlayer maniac = findManiac(players);
        tickCarried(maniac);
        tickHangs(maniac);

        if (tickCounter % 5 == 0) syncGlowTeams();
        if (maniac != null && isCarrying(maniac.getUUID()) && tickCounter % 40 == 0) {
            sendHighlight(maniac);
        }
    }

    private void clearAll(List<ServerPlayer> players) {
        releaseEveryone(players);
        for (UUID id : new ArrayList<>(hookIds)) {
            Entity entity = findEntity(id);
            if (entity != null) entity.discard();
        }
        hookIds.clear();
        repickupBlockedUntil.clear();
        timerPaused.clear();
        for (ServerPlayer player : players) clearTeam(player);
    }

    /** Знімає всіх із плечей і гаків БЕЗ телепорту (кінець фази / матчу). */
    private void releaseEveryone(List<ServerPlayer> players) {
        for (UUID victim : new ArrayList<>(carried.keySet())) discardSeat(victim, true);
        for (UUID victim : new ArrayList<>(hangs.keySet())) discardSeat(victim, true);
        carried.clear();
        hangs.clear();
        for (UUID hookId : new ArrayList<>(hookVictim.keySet())) setHookOccupied(hookId, false);
        hookVictim.clear();
        for (ServerPlayer p : players) {
            if (match().isManiac(p.getUUID())) {
                ModNetwork.toPlayer(p, new HookHighlightPacket(List.of()));
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Спавн гаків
    // ═════════════════════════════════════════════════════════════════════

    /** Спавнить ВСІ розмічені гаки матчу. Нуль гаків — не помилка (карта без гаків). */
    public void spawnHooks(ServerLevel level, List<SpawnPoint> allPoints) {
        if (level == null) return;
        for (SpawnPoint point : allPoints) {
            if (point.kind() != SpawnPointKind.HOOK) continue;
            spawnHookAt(level, point.pos().getX() + 0.5, point.pos().getY(),
                point.pos().getZ() + 0.5, point.yaw());
        }
    }

    /** Дебаг/тест: гак у довільній точці (не з розмітки). */
    public HookEntity spawnHookAt(ServerLevel level, double x, double y, double z, float heading) {
        HookEntity hook = HookEntity.spawn(ModEntityTypes.HOOK.get(), level, x, y, z, heading);
        hookIds.add(hook.getUUID());
        return hook;
    }

    public boolean owns(HookEntity hook) {
        return hookIds.contains(hook.getUUID());
    }

    public boolean ownsSeat(VictimSeatEntity seat) {
        UUID id = seat.getUUID();
        for (Carry c : carried.values()) if (c.seatId.equals(id)) return true;
        for (Hang h : hangs.values()) if (h.seatId.equals(id)) return true;
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Запити (для SurvivorModule, клієнтських пакетів, команд)
    // ═════════════════════════════════════════════════════════════════════

    public HookStatus statusOf(UUID victim) {
        if (carried.containsKey(victim)) return HookStatus.CARRIED;
        if (hangs.containsKey(victim)) return HookStatus.HOOKED;
        return match().survivorStateOf(victim) == SurvivorState.UNCONSCIOUS
            ? HookStatus.LYING : HookStatus.NONE;
    }

    /** Чи гравець зараз на плечі або на гаку (їде на сидінні). */
    public boolean isSeated(UUID victim) {
        return carried.containsKey(victim) || hangs.containsKey(victim);
    }

    public boolean isCarried(UUID victim) {
        return carried.containsKey(victim);
    }

    public boolean isCarrying(UUID maniacId) {
        for (Carry c : carried.values()) if (c.maniacId.equals(maniacId)) return true;
        return false;
    }

    /** Чи жертву зараз можна піднімати союзниками: на плечі маньяка — ні. */
    public boolean canBeRescued(UUID victim) {
        return !carried.containsKey(victim);
    }

    /**
     * Чи таймер цього лежачого веде цей модуль (а не звичайна кровотеча SurvivorModule).
     * Лише для ОНЛАЙН-гравців: тіло гравця, що вийшов, вмирає за звичайним таймером.
     */
    public boolean managesTimer(UUID victim) {
        if (match().onlinePlayer(victim) == null) return false;
        if (isSeated(victim)) return true;
        return !ManiacConfigs.get(ConfigSchema.HOOK_LYING_BLEEDS_OUT);
    }

    public boolean isTimerPaused(UUID victim) {
        return timerPaused.contains(victim);
    }

    /** Чи показувати клієнтам таймер: лише висячому (або лежачому, якщо лежання теж убиває). */
    public boolean timerVisible(UUID victim) {
        if (carried.containsKey(victim)) return false;
        if (hangs.containsKey(victim)) return true;
        return ManiacConfigs.get(ConfigSchema.HOOK_LYING_BLEEDS_OUT);
    }

    public int freeHookCount() {
        int free = 0;
        for (UUID id : hookIds) if (!hookVictim.containsKey(id) && findEntity(id) != null) free++;
        return free;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ПКМ маньяка: підняти на плече / повісити на гак
    // ═════════════════════════════════════════════════════════════════════

    /**
     * ПКМ гравця по сутності. Викликається з події {@code EntityInteract}.
     *
     * @return true, якщо подію треба «з'їсти» (скасувати ванільну взаємодію); false — не наш випадок
     */
    public boolean onManiacInteract(ServerPlayer maniac, Entity target) {
        MatchOrchestrator m = match();
        if (!m.isManiac(maniac.getUUID())) return false;
        if (!com.log_to_kot.maniacmod.maniacs.ManiacCombatModule.damageAllowed()) return false;

        boolean relevant = target instanceof HookEntity
            || (target instanceof ServerPlayer p && m.isSurvivor(p.getUUID()));
        if (!relevant) return false;

        // Оглушений маньяк «нічого не може» — і не піднімає, і не вішає.
        if (m.maniacStun().isStunned(maniac.getUUID())) return true;

        if (target instanceof HookEntity hook) {
            if (!owns(hook)) return false;
            if (isCarrying(maniac.getUUID())) tryHang(maniac, hook);
            return true;
        }

        ServerPlayer victim = (ServerPlayer) target;
        if (statusOf(victim.getUUID()) == HookStatus.LYING) {
            tryPickUp(maniac, victim);
        }
        // Висячого/несеного/здорового ПКМ не чіпає, але й ванільного «взаємодіяти» не буде.
        return true;
    }

    private void tryPickUp(ServerPlayer maniac, ServerPlayer victim) {
        if (isCarrying(maniac.getUUID())) {
            notify(maniac, ActionBarMessageType.ERROR, "maniacmod.hook.already_carrying");
            return;
        }
        double range = ManiacConfigs.get(ConfigSchema.HOOK_PICKUP_RANGE_BLOCKS);
        if (distSqToBox(maniac.getEyePosition(), victim.getBoundingBox()) > range * range) return;

        Long blocked = repickupBlockedUntil.get(victim.getUUID());
        if (blocked != null && maniac.level().getGameTime() < blocked) return;

        // Союзники, що тримали ПКМ на ньому, більше нічого не підіймають.
        match().survivors().cancelRescue(victim.getUUID());

        VictimSeatEntity seat = VictimSeatEntity.spawn(ModEntityTypes.VICTIM_SEAT.get(),
            victim.serverLevel(), maniac.getX(), maniac.getY(), maniac.getZ());
        if (seat == null) return;
        seat.setStatus(HookStatus.CARRIED);
        seat.setCarrierId(maniac.getId());
        positionCarrySeat(seat, maniac);

        victim.setSprinting(false);
        victim.setDeltaMovement(Vec3.ZERO);
        if (!victim.startRiding(seat, true)) {
            seat.discard();
            return;
        }
        carried.put(victim.getUUID(), new Carry(maniac.getUUID(), seat.getUUID()));
        victim.setGlowingTag(false);

        notify(maniac, ActionBarMessageType.INFO, "maniacmod.hook.carrying", victim.getGameProfile().getName());
        notify(victim, ActionBarMessageType.ERROR, "maniacmod.hook.you_carried");
        sendHighlight(maniac);
    }

    private void tryHang(ServerPlayer maniac, HookEntity hook) {
        UUID victimId = victimCarriedBy(maniac.getUUID());
        if (victimId == null) return;
        ServerPlayer victim = match().onlinePlayer(victimId);
        if (victim == null) return;

        double range = ManiacConfigs.get(ConfigSchema.HOOK_USE_RANGE_BLOCKS);
        if (distSqToBox(maniac.getEyePosition(), hook.getBoundingBox()) > range * range) return;
        if (hookVictim.containsKey(hook.getUUID())) {
            notify(maniac, ActionBarMessageType.ERROR, "maniacmod.hook.occupied");
            return;
        }

        Carry carry = carried.remove(victimId);
        Entity seatEntity = findEntity(carry.seatId);
        if (!(seatEntity instanceof VictimSeatEntity seat)) {
            // Сидіння зникло — жертва лишається лежати, повісити нікого.
            victim.stopRiding();
            return;
        }
        int ticks = ManiacConfigs.get(ConfigSchema.HOOK_HANG_SECONDS) * 20;
        hangs.put(victimId, new Hang(hook.getUUID(), seat.getUUID(), ticks));
        hookVictim.put(hook.getUUID(), victimId);
        match().recordStat(victimId, com.log_to_kot.maniacmod.core.match.MatchStat.HOOKS, 1);
        hook.setOccupied(true);
        seat.setStatus(HookStatus.HOOKED);
        seat.setCarrierId(-1);
        positionHangSeat(seat, hook);
        match().survivors().setDownedTicksLeft(victimId, ticks);
        victim.setGlowingTag(true);   // на гаку знову світиться (на плечі було вимкнено)

        notify(maniac, ActionBarMessageType.INFO, "maniacmod.hook.hooked", victim.getGameProfile().getName());
        notify(victim, ActionBarMessageType.ERROR, "maniacmod.hook.you_hooked");
        sendHighlight(maniac);
    }

    private UUID victimCarriedBy(UUID maniacId) {
        for (var e : carried.entrySet()) if (e.getValue().maniacId.equals(maniacId)) return e.getKey();
        return null;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Щотіка
    // ═════════════════════════════════════════════════════════════════════

    private void tickCarried(ServerPlayer maniac) {
        for (UUID victimId : new ArrayList<>(carried.keySet())) {
            Carry carry = carried.get(victimId);
            ServerPlayer victim = match().onlinePlayer(victimId);
            ServerPlayer carrier = match().onlinePlayer(carry.maniacId);
            boolean alive = victim != null && carrier != null
                && match().survivorStateOf(victimId) == SurvivorState.UNCONSCIOUS
                && findEntity(carry.seatId) instanceof VictimSeatEntity;
            if (!alive) {
                dropCarried(victimId, carrier);
                continue;
            }
            VictimSeatEntity seat = (VictimSeatEntity) findEntity(carry.seatId);
            ensureRiding(victim, seat);
            positionCarrySeat(seat, carrier);
            // На плечі жертва НЕ світиться: контур — лише коли лежить або висить на гаку.
            // Щотіка, бо інші модулі (SurvivorModule.setDownedGlow) могли ввімкнути його знову.
            if (victim.isCurrentlyGlowing()) victim.setGlowingTag(false);
        }
    }

    private void tickHangs(ServerPlayer maniac) {
        timerPaused.clear();
        int pauseRadius = ManiacConfigs.get(ConfigSchema.HOOK_PAUSE_RADIUS_BLOCKS);

        for (UUID victimId : new ArrayList<>(hangs.keySet())) {
            Hang hang = hangs.get(victimId);
            ServerPlayer victim = match().onlinePlayer(victimId);
            Entity hookEntity = findEntity(hang.hookId);
            Entity seatEntity = findEntity(hang.seatId);
            boolean alive = victim != null
                && match().survivorStateOf(victimId) == SurvivorState.UNCONSCIOUS
                && hookEntity instanceof HookEntity
                && seatEntity instanceof VictimSeatEntity;
            if (!alive) {
                // Гак чи сидіння зникли (чанк, команда) — знімаємо без телепорту в повітря.
                releaseHang(victimId, victim != null && hookEntity instanceof HookEntity);
                continue;
            }
            HookEntity hook = (HookEntity) hookEntity;
            VictimSeatEntity seat = (VictimSeatEntity) seatEntity;

            ensureRiding(victim, seat);
            positionHangSeat(seat, hook);

            // Таймер: стоїть, поки маньяк ближче за радіус паузи (або його немає онлайн).
            boolean paused;
            if (maniac == null) {
                paused = true;
            } else if (pauseRadius <= 0) {
                paused = false;
            } else {
                paused = maniac.level() == victim.level()
                    && maniac.distanceToSqr(victim) <= (double) pauseRadius * pauseRadius;
            }
            if (paused) {
                timerPaused.add(victimId);
            } else {
                hang.ticksLeft--;
            }
            match().survivors().setDownedTicksLeft(victimId, Math.max(0, hang.ticksLeft));

            if (hang.ticksLeft <= 0) {
                match().survivors().expireDowned(victimId);
            }
        }
    }

    /** Зісканути Shift-ом не можна: повертаємо на сидіння (так само, як ClimbCarrier). */
    private void ensureRiding(ServerPlayer victim, VictimSeatEntity seat) {
        if (victim.getVehicle() != seat) {
            victim.startRiding(seat, true);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Позиції — ОДНЕ місце, де вирішується «де саме висить/лежить»
    // ═════════════════════════════════════════════════════════════════════

    /**
     * На плечі: від тіла маньяка. Висота — частка його зросту, щоб працювало для низького й
     * високого архетипу однаково; решта — блоки (hooks.carry*).
     */
    private void positionCarrySeat(VictimSeatEntity seat, ServerPlayer maniac) {
        float bodyYaw = maniac.yBodyRot;
        double rad = Math.toRadians(bodyYaw);
        double fx = -Math.sin(rad), fz = Math.cos(rad);
        double rx = -Math.cos(rad), rz = -Math.sin(rad);

        double forward = ManiacConfigs.get(ConfigSchema.HOOK_CARRY_FORWARD);
        double side = ManiacConfigs.get(ConfigSchema.HOOK_CARRY_SIDE);
        double heightFraction = ManiacConfigs.get(ConfigSchema.HOOK_CARRY_HEIGHT_FRACTION);
        double yawOffset = ManiacConfigs.get(ConfigSchema.HOOK_CARRY_YAW);

        seat.setPos(
            maniac.getX() + fx * forward + rx * side,
            maniac.getY() + maniac.getBbHeight() * heightFraction,
            maniac.getZ() + fz * forward + rz * side);
        seat.setBodyYaw((float) wrap(bodyYaw + yawOffset));
    }

    /** На гаку: від гака, в системі «куди дивиться гак». */
    private void positionHangSeat(VictimSeatEntity seat, HookEntity hook) {
        Vec3 forwardVec = hook.forward();
        Vec3 rightVec = hook.right();
        double forward = ManiacConfigs.get(ConfigSchema.HOOK_HANG_FORWARD);
        double side = ManiacConfigs.get(ConfigSchema.HOOK_HANG_SIDE);
        double up = ManiacConfigs.get(ConfigSchema.HOOK_HANG_UP);
        double yawOffset = ManiacConfigs.get(ConfigSchema.HOOK_HANG_YAW);

        seat.setPos(
            hook.getX() + forwardVec.x * forward + rightVec.x * side,
            hook.getY() + up,
            hook.getZ() + forwardVec.z * forward + rightVec.z * side);
        seat.setBodyYaw((float) wrap(hook.heading() + yawOffset));
    }

    private static double wrap(double yaw) {
        double y = yaw % 360.0;
        return y < 0 ? y + 360.0 : y;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Зняти / скинути (виклики з інших модулів)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Союзники підняли жертву (завершено ПКМ×8 с). Вішака знімає на землю перед гаком; несеного
     * підняти не можна, але про всяк випадок кладемо. Викликається ДО зміни стану на HEALTHY.
     */
    public void onVictimRevived(UUID victimId) {
        if (hangs.containsKey(victimId)) {
            releaseHang(victimId, true);
        } else if (carried.containsKey(victimId)) {
            dropCarried(victimId, match().onlinePlayer(carried.get(victimId).maniacId));
        }
    }

    /** Жертва вибула (смерть, вихід із матчу): прибрати сидіння й звільнити гак, нікуди не телепортуючи. */
    public void onVictimGone(UUID victimId) {
        if (hangs.containsKey(victimId)) {
            releaseHang(victimId, false);
        } else if (carried.containsKey(victimId)) {
            ServerPlayer carrier = match().onlinePlayer(carried.get(victimId).maniacId);
            carried.remove(victimId);
            discardSeat(victimId, false);
            if (carrier != null) sendHighlight(carrier);
        }
        repickupBlockedUntil.remove(victimId);
        timerPaused.remove(victimId);
        ServerPlayer player = match().onlinePlayer(victimId);
        if (player != null) clearTeam(player);
    }

    /** Оглушили маньяка, що несе, — жертва падає на землю й знову лежить. */
    public void onManiacStunned(UUID maniacId) {
        UUID victimId = victimCarriedBy(maniacId);
        if (victimId == null) return;
        ServerPlayer maniac = match().onlinePlayer(maniacId);
        dropCarried(victimId, maniac);
        if (maniac != null) notify(maniac, ActionBarMessageType.ERROR, "maniacmod.hook.dropped_stun");
    }

    /** Гравець вийшов із сервера (викликається ДО SurvivorModule#onPlayerLeft). */
    public void onPlayerLeft(ServerPlayer player) {
        UUID id = player.getUUID();
        if (isCarrying(id)) {
            UUID victimId = victimCarriedBy(id);
            dropCarried(victimId, player);
            return;
        }
        if (hangs.containsKey(id)) {
            // Тіло вже створене lib на місці гравця (у повітрі, біля гака). Знімаємо сидіння і
            // кладемо ТІЛО перед гаком, а не лишаємо висіти.
            Hang hang = hangs.get(id);
            Entity hookEntity = findEntity(hang.hookId);
            releaseHang(id, false);
            if (hookEntity instanceof HookEntity hook && ManiacOffline.hasBody(id)) {
                var server = match().server();
                if (server != null) {
                    ManiacOffline.actor(server, id).ifPresent(body -> {
                        Vec3 spot = groundSpotInFront(hook);
                        ManiacOffline.placeAt(body, (ServerLevel) hook.level(), spot.x, spot.y, spot.z,
                            body.getYRot(), 0f);
                    });
                }
            }
        } else if (carried.containsKey(id)) {
            carried.remove(id);
            discardSeat(id, false);
        }
        clearTeam(player);
    }

    /** Гравець, що вийшов, повернувся: нічого не тримаємо. */
    public void forget(UUID id) {
        repickupBlockedUntil.remove(id);
    }

    // ── внутрішнє зняття ─────────────────────────────────────────────────

    private void dropCarried(UUID victimId, ServerPlayer carrier) {
        Carry carry = carried.remove(victimId);
        if (carry == null) return;
        ServerPlayer victim = match().onlinePlayer(victimId);
        Entity seat = findEntity(carry.seatId);

        double x, y, z;
        if (carrier != null) {
            double rad = Math.toRadians(carrier.yBodyRot);
            x = carrier.getX() - Math.sin(rad) * 0.9;
            z = carrier.getZ() + Math.cos(rad) * 0.9;
            y = carrier.getY();
        } else if (seat != null) {
            x = seat.getX();
            y = groundYBelow(seat);
            z = seat.getZ();
        } else {
            x = victim != null ? victim.getX() : 0;
            y = victim != null ? victim.getY() : 0;
            z = victim != null ? victim.getZ() : 0;
        }

        if (victim != null) {
            victim.stopRiding();
            victim.setDeltaMovement(Vec3.ZERO);
            victim.connection.teleport(x, y, z, victim.getYRot(), 0f);
            // Впала на землю й знову лежить — контур повертається (смерть/підняття не чіпаємо).
            if (match().survivorStateOf(victimId) == SurvivorState.UNCONSCIOUS) victim.setGlowingTag(true);
        }
        if (seat != null) seat.discard();

        int delay = ManiacConfigs.get(ConfigSchema.HOOK_REPICKUP_DELAY_TICKS);
        if (delay > 0 && victim != null) {
            repickupBlockedUntil.put(victimId, victim.level().getGameTime() + delay);
        }
        if (carrier != null) sendHighlight(carrier);
    }

    /**
     * Знімає жертву з гака. {@code placeInFront}: поставити на землю перед гаком (союзники зняли),
     * інакше лишити де є (гак зник / вибув / вийшов).
     */
    private void releaseHang(UUID victimId, boolean placeInFront) {
        Hang hang = hangs.remove(victimId);
        if (hang == null) return;
        hookVictim.remove(hang.hookId);
        Entity hookEntity = findEntity(hang.hookId);
        if (hookEntity instanceof HookEntity hook) hook.setOccupied(false);

        ServerPlayer victim = match().onlinePlayer(victimId);
        Entity seat = findEntity(hang.seatId);
        if (victim != null) {
            victim.stopRiding();
            victim.setDeltaMovement(Vec3.ZERO);
            if (placeInFront && hookEntity instanceof HookEntity hook) {
                Vec3 spot = groundSpotInFront(hook);
                // Обличчям до гака, як і висів.
                float yaw = (float) wrap(hook.heading() + 180.0);
                victim.connection.teleport(spot.x, spot.y, spot.z, yaw, 0f);
            }
        }
        if (seat != null) seat.discard();
        timerPaused.remove(victimId);

        // Маньяк, що саме несе іншого, бачить, що цей гак звільнився.
        for (Carry c : carried.values()) {
            ServerPlayer carrier = match().onlinePlayer(c.maniacId);
            if (carrier != null) sendHighlight(carrier);
        }
    }

    private void discardSeat(UUID victimId, boolean dismount) {
        UUID seatId = null;
        Carry c = carried.get(victimId);
        if (c != null) seatId = c.seatId;
        Hang h = hangs.get(victimId);
        if (h != null) seatId = h.seatId;
        if (seatId == null) return;
        if (dismount) {
            ServerPlayer victim = match().onlinePlayer(victimId);
            if (victim != null) victim.stopRiding();
        }
        Entity seat = findEntity(seatId);
        if (seat != null) seat.discard();
        if (h != null) {
            hookVictim.remove(h.hookId);
            if (findEntity(h.hookId) instanceof HookEntity hook) hook.setOccupied(false);
        }
    }

    private void setHookOccupied(UUID hookId, boolean occupied) {
        if (findEntity(hookId) instanceof HookEntity hook) hook.setOccupied(occupied);
    }

    private Vec3 groundSpotInFront(HookEntity hook) {
        double front = ManiacConfigs.get(ConfigSchema.HOOK_UNHOOK_FRONT_BLOCKS);
        Vec3 f = hook.forward();
        return new Vec3(hook.getX() + f.x * front, hook.getY(), hook.getZ() + f.z * front);
    }

    private double groundYBelow(Entity from) {
        var level = from.level();
        net.minecraft.core.BlockPos.MutableBlockPos pos = from.blockPosition().mutable();
        for (int i = 0; i < 8; i++) {
            if (!level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()) {
                return pos.getY();
            }
            pos.move(0, -1, 0);
        }
        return from.getY();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Підсвітка
    // ═════════════════════════════════════════════════════════════════════

    /** Вільні гаки — лише маньяку, що несе; порожній список гасить підсвітку. */
    private void sendHighlight(ServerPlayer maniac) {
        List<HookHighlightPacket.Entry> entries = new ArrayList<>();
        if (isCarrying(maniac.getUUID())) {
            for (UUID id : hookIds) {
                if (hookVictim.containsKey(id)) continue;
                Entity hook = findEntity(id);
                if (hook == null) continue;
                entries.add(new HookHighlightPacket.Entry(hook.getId(),
                    hook.getX(), hook.getY(), hook.getZ()));
            }
        }
        ModNetwork.toPlayer(maniac, new HookHighlightPacket(entries));
    }

    /** Колір контуру (команда скорборда) під статус жертви: його бачать усі, хто бачить гравця. */
    private void syncGlowTeams() {
        for (UUID id : match().survivorIds()) {
            ServerPlayer player = match().onlinePlayer(id);
            if (player == null) continue;
            applyTeam(player, statusOf(id));
        }
    }

    private void applyTeam(ServerPlayer player, HookStatus status) {
        Scoreboard scoreboard = player.serverLevel().getScoreboard();
        String member = player.getScoreboardName();
        PlayerTeam current = scoreboard.getPlayersTeam(member);

        // Несуть (CARRIED) — без кольорової команди, як і здоровий: жертва на плечі не світиться.
        if (status == HookStatus.NONE || status == HookStatus.CARRIED) {
            if (current != null && current.getName().startsWith(TEAM_PREFIX)) {
                scoreboard.removePlayerFromTeam(member, current);
            }
            return;
        }
        PlayerTeam wanted = teamFor(scoreboard, status);
        if (current == wanted) return;
        // Чужу команду (адмінську) не вибиваємо: лише свою, гакову або «непритомного».
        boolean ours = current == null || current.getName().startsWith(TEAM_PREFIX)
            || current.getName().equals(LEGACY_DOWNED_TEAM);
        if (ours) scoreboard.addPlayerToTeam(member, wanted);
    }

    private void clearTeam(ServerPlayer player) {
        Scoreboard scoreboard = player.serverLevel().getScoreboard();
        PlayerTeam current = scoreboard.getPlayersTeam(player.getScoreboardName());
        if (current != null && current.getName().startsWith(TEAM_PREFIX)) {
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), current);
        }
    }

    private PlayerTeam teamFor(Scoreboard scoreboard, HookStatus status) {
        String name = TEAM_PREFIX + status.name().toLowerCase();
        PlayerTeam team = scoreboard.getPlayerTeam(name);
        if (team == null) {
            team = scoreboard.addPlayerTeam(name);
            team.setColor(switch (status) {
                case CARRIED -> ChatFormatting.LIGHT_PURPLE;
                case HOOKED -> ChatFormatting.RED;
                default -> ChatFormatting.GOLD;
            });
        }
        return team;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Допоміжне
    // ═════════════════════════════════════════════════════════════════════

    private Entity findEntity(UUID id) {
        var server = match().server();
        if (server == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    private ServerPlayer findManiac(List<ServerPlayer> players) {
        for (ServerPlayer p : players) if (match().isManiac(p.getUUID())) return p;
        return null;
    }

    private static double distSqToBox(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0), point.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private void notify(ServerPlayer player, ActionBarMessageType type, String key, String... args) {
        ModNetwork.toPlayer(player, new ActionBarPacket(type, key, args));
    }

    // ── Дебаг-команди ────────────────────────────────────────────────────

    /** {@code /maniac hook clear}: прибрати всі гаки, нікого не лишивши висіти. */
    public void debugClear() {
        List<ServerPlayer> players = match().onlinePlayers();
        clearAll(players);
    }

    /** {@code /maniac hook hang}: повісити вказаного (UNCONSCIOUS) гравця на найближчий вільний гак. */
    public boolean debugHang(ServerPlayer victim) {
        if (match().survivorStateOf(victim.getUUID()) != SurvivorState.UNCONSCIOUS) return false;
        if (isSeated(victim.getUUID())) return false;
        HookEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (UUID id : hookIds) {
            if (hookVictim.containsKey(id)) continue;
            if (!(findEntity(id) instanceof HookEntity hook) || hook.level() != victim.level()) continue;
            double d = hook.distanceToSqr(victim);
            if (d < best) { best = d; nearest = hook; }
        }
        if (nearest == null) return false;

        match().survivors().cancelRescue(victim.getUUID());
        VictimSeatEntity seat = VictimSeatEntity.spawn(ModEntityTypes.VICTIM_SEAT.get(),
            victim.serverLevel(), nearest.getX(), nearest.getY(), nearest.getZ());
        if (seat == null) return false;
        seat.setStatus(HookStatus.HOOKED);
        int ticks = ManiacConfigs.get(ConfigSchema.HOOK_HANG_SECONDS) * 20;
        hangs.put(victim.getUUID(), new Hang(nearest.getUUID(), seat.getUUID(), ticks));
        hookVictim.put(nearest.getUUID(), victim.getUUID());
        nearest.setOccupied(true);
        positionHangSeat(seat, nearest);
        if (!victim.startRiding(seat, true)) {
            releaseHang(victim.getUUID(), false);
            return false;
        }
        match().survivors().setDownedTicksLeft(victim.getUUID(), ticks);
        return true;
    }
}
