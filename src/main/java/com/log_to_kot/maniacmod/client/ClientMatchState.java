package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.map.zones.GeneratorPoi;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorHighlightPacket;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.DownedSurvivorsPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Клієнтське дзеркало стану матчу.
 *
 * Усе, що HUD, клавіші й рендер мають знати про поточну гру, лежить
 * тут — і оновлюється ТІЛЬКИ пакетами з сервера. Жоден клієнтський
 * клас не рахує ігровий стан сам.
 *
 * v3-еквівалент: client/ManiacClientState — там було лише два поля
 * (чи я маньяк + тип), тому клієнт виживого фактично нічого не знав
 * про матч, і кожен оверлей тримав власні статичні прапорці, які
 * ніхто не скидав при виході з гри (див. reset()).
 */
public final class ClientMatchState {

    // ── Фаза ─────────────────────────────────────────────────────────────
    private static GamePhase phase = GamePhase.LOBBY;

    // ── Роль ─────────────────────────────────────────────────────────────
    private static RoleSyncPacket.Role role = RoleSyncPacket.Role.SPECTATOR;
    private static String archetypeId = "";

    // ── Показники виживого ───────────────────────────────────────────────
    private static int hp = 0;
    private static int maxHp = 0;
    private static float stamina = 1f;
    private static SurvivorState survivorState = SurvivorState.HEALTHY;
    private static float heartbeat = 0f;

    // ── Прогрес вставання після падіння (StandUpProgressPacket) ──────────
    // required == 0 означає "вставання не триває" — шкалу не малювати.
    private static int standUpPresses = 0;
    private static int standUpRequired = 0;

    // ── Кулдауни здібностей: id → тік клієнта, коли кулдаун завершиться ──
    private static final Map<String, Long> abilityReadyAt = new java.util.HashMap<>();
    private static final Map<String, Integer> abilityTotal = new java.util.HashMap<>();

    // ── Підсвітка генераторів ────────────────────────────────────────────
    private static List<GeneratorHighlightPacket.Entry> highlight = List.of();
    private static long highlightUntilTick = 0;

    // ── Лежачі виживі (DownedSurvivorsPacket) ───────────────────────────
    // Джерело правди для ПОЗИ (DownedPose), мітки на карту й таймера до
    // смерті. Час між пакетами клієнт відлічує сам від downedReceivedAtMs.
    private static List<DownedSurvivorsPacket.Entry> downed = List.of();
    private static long downedReceivedAtMs = 0;
    /** Найбільший ticksLeft, побачений для кожного лежачого, — знаменник шкали часу. */
    private static final Map<UUID, Integer> downedMaxTicks = new java.util.HashMap<>();

    // ── Прогрес підняття (RescueProgressPacket) ──────────────────────────
    // Шкала живе, поки пакети приходять: якщо нового не було довше за
    // RESCUE_UI_TIMEOUT_MS — вважаємо, що підняття зупинилось.
    private static final long RESCUE_UI_TIMEOUT_MS = 250;
    private static int rescueProgress = 0;
    private static int rescueRequired = 0;
    private static boolean rescueAsVictim = false;
    private static long rescueUpdatedAtMs = 0;

    // ── Ростер (tab-екран) ───────────────────────────────────────────────
    private static List<RosterSyncPacket.RosterEntry> roster = List.of();

    // ── Візуальні налаштування предметів на землі (loot.sparkleEnabled) ──
    // НАВМИСНО поза reset(): це конфіг сервера, а не прогрес матчу; вихід
    // у LOBBY/RESET не повинен вимикати блиск до наступного пакета.
    private static boolean groundItemSparkleEnabled = true;

    private ClientMatchState() {}

    // ── Запис (викликається лише з ClientPacketHandler) ──────────────────

    static void setPhase(GamePhase next) {
        phase = next;
        // Вихід із матчу гасить усе, що показує HUD. У v3 це доводилось
        // робити в кожному оверлеї окремо — і про половину забували.
        if (next == GamePhase.LOBBY || next == GamePhase.RESET) reset();
    }

    static void setRole(RoleSyncPacket.Role newRole, String newArchetypeId) {
        role = newRole;
        archetypeId = newArchetypeId;
    }

    static void setVitals(int newHp, int newMaxHp, float newStamina,
                          SurvivorState state, float newHeartbeat) {
        hp = newHp;
        maxHp = newMaxHp;
        stamina = newStamina;
        survivorState = state;
        heartbeat = newHeartbeat;
    }

    static void setStandUpProgress(int presses, int required) {
        standUpPresses = Math.max(0, presses);
        standUpRequired = Math.max(0, required);
    }

    static void setAbilityCooldown(String abilityId, int totalTicks, long currentTick) {
        if (totalTicks <= 0) {
            abilityReadyAt.remove(abilityId);
            abilityTotal.remove(abilityId);
            return;
        }
        abilityReadyAt.put(abilityId, currentTick + totalTicks);
        abilityTotal.put(abilityId, totalTicks);
    }

    static void setHighlight(List<GeneratorHighlightPacket.Entry> entries,
                             int durationTicks, long currentTick) {
        highlight = List.copyOf(entries);
        highlightUntilTick = currentTick + durationTicks;
    }

    static void setDowned(List<DownedSurvivorsPacket.Entry> entries) {
        downed = List.copyOf(entries);
        downedReceivedAtMs = System.currentTimeMillis();
        java.util.Set<UUID> present = new java.util.HashSet<>();
        for (DownedSurvivorsPacket.Entry entry : entries) {
            present.add(entry.id());
            downedMaxTicks.merge(entry.id(), entry.ticksLeft(), Math::max);
        }
        downedMaxTicks.keySet().retainAll(present);
    }

    static void setRescueProgress(int progress, int required, boolean asVictim) {
        rescueProgress = Math.max(0, progress);
        rescueRequired = Math.max(0, required);
        rescueAsVictim = asVictim;
        rescueUpdatedAtMs = System.currentTimeMillis();
    }

    static void setRoster(List<RosterSyncPacket.RosterEntry> entries) {
        roster = List.copyOf(entries);
    }

    static void setGroundItemSparkleEnabled(boolean enabled) {
        groundItemSparkleEnabled = enabled;
    }

    /** Повне скидання. Викликається при виході з матчу і при диконекті. */
    public static void reset() {
        role = RoleSyncPacket.Role.SPECTATOR;
        archetypeId = "";
        hp = 0;
        maxHp = 0;
        stamina = 1f;
        survivorState = SurvivorState.HEALTHY;
        heartbeat = 0f;
        standUpPresses = 0;
        standUpRequired = 0;
        abilityReadyAt.clear();
        abilityTotal.clear();
        highlight = List.of();
        highlightUntilTick = 0;
        roster = List.of();
        downed = List.of();
        downedMaxTicks.clear();
        rescueRequired = 0;
        rescueProgress = 0;
        com.log_to_kot.maniacmod.client.overlay.WorldToScreen.invalidate();
        com.log_to_kot.maniacmod.client.overlay.actionprogress.GeneratorProgressOverlay.reset();
        com.log_to_kot.maniacmod.client.overlay.notify.GeneratorExplosionMarker.reset();
        com.log_to_kot.maniacmod.client.overlay.notify.GeneratorHighlightMarker.reset();
    }

    // ── Читання ──────────────────────────────────────────────────────────

    /** Чи цей гравець (будь-який, не лише я) зараз лежить непритомним. */
    public static boolean isDowned(UUID id) {
        for (DownedSurvivorsPacket.Entry entry : downed) {
            if (entry.id().equals(id)) return true;
        }
        return false;
    }

    public static List<DownedSurvivorsPacket.Entry> downedEntries() { return downed; }

    /** Скільки мс лишилось лежачому: серверне значення мінус те, що минуло від пакета. */
    public static long downedMillisLeft(DownedSurvivorsPacket.Entry entry) {
        long elapsed = System.currentTimeMillis() - downedReceivedAtMs;
        return Math.max(0L, entry.ticksLeft() * 50L - elapsed);
    }

    /** Частка часу, що ще лишилась (1 — щойно ліг, 0 — час вийшов). */
    public static float downedFraction(DownedSurvivorsPacket.Entry entry) {
        int max = downedMaxTicks.getOrDefault(entry.id(), entry.ticksLeft());
        if (max <= 0) return 0f;
        return Math.max(0f, Math.min(1f, downedMillisLeft(entry) / (max * 50f)));
    }

    /** Чи підняття йде просто зараз (пакети прогресу ще свіжі). */
    public static boolean rescueActive() {
        return rescueRequired > 0
            && System.currentTimeMillis() - rescueUpdatedAtMs < RESCUE_UI_TIMEOUT_MS;
    }

    public static float rescueFraction() {
        return rescueRequired <= 0 ? 0f : Math.max(0f, Math.min(1f, (float) rescueProgress / rescueRequired));
    }

    /** true — це пакет для того, кого піднімають; false — для рятівника. */
    public static boolean rescueAsVictim() { return rescueAsVictim; }


    public static GamePhase phase()            { return phase; }
    public static boolean isGameplay()         { return phase.isGameplay(); }
    public static boolean allows(PhaseRule r)  { return phase.allows(r); }

    public static boolean isManiac()           { return role == RoleSyncPacket.Role.MANIAC; }
    public static boolean isSurvivor()         { return role == RoleSyncPacket.Role.SURVIVOR; }
    public static RoleSyncPacket.Role role()   { return role; }
    public static String archetypeId()         { return archetypeId; }

    public static int hp()                     { return hp; }
    public static int maxHp()                  { return maxHp; }
    public static float stamina()              { return stamina; }
    public static SurvivorState survivorState() { return survivorState; }
    public static float heartbeat()            { return heartbeat; }

    public static boolean groundItemSparkleEnabled() { return groundItemSparkleEnabled; }

    /** Скільки натискань пробілу вже зараховано (0, якщо вставання не триває). */
    public static int standUpPresses()         { return standUpPresses; }
    /** Скільки натискань треба всього. 0 = вставання не триває, шкалу ховати. */
    public static int standUpRequired()        { return standUpRequired; }
    /** Чи гравець зараз намагається встати (шкалу треба малювати). */
    public static boolean isStandingUp()       { return standUpRequired > 0; }

    /** Частка кулдауну, що лишилась: 1.0 щойно активовано, 0.0 готово. */
    public static float abilityCooldownFraction(String abilityId, long currentTick) {
        Long readyAt = abilityReadyAt.get(abilityId);
        Integer total = abilityTotal.get(abilityId);
        if (readyAt == null || total == null || total <= 0) return 0f;
        long left = readyAt - currentTick;
        if (left <= 0) return 0f;
        return Math.min(1f, (float) left / total);
    }

    /** Скільки ТІКІВ лишилось до кінця кулдауну (0 — готово). Для підписів у секундах. */
    public static long abilityCooldownTicksLeft(String abilityId, long currentTick) {
        Long readyAt = abilityReadyAt.get(abilityId);
        if (readyAt == null) return 0L;
        return Math.max(0L, readyAt - currentTick);
    }

    /** Генератори, які зараз підсвічені. Порожньо, якщо підсвітка згасла. */
    public static List<GeneratorHighlightPacket.Entry> activeHighlight(long currentTick) {
        return currentTick < highlightUntilTick ? highlight : List.of();
    }

    /** Колір підсвітки конкретного стану. Одне місце — щоб HUD і світ збігались. */
    public static int highlightColor(GeneratorPoi.VisualState state) {
        return switch (state) {
            case IDLE        -> 0xFFFFFFFF; // білий
            case IN_PROGRESS -> 0xFFFFD54F; // жовтий
            case FAILED      -> 0xFFE53935; // червоний
            case DONE        -> 0xFF43A047; // зелений
        };
    }

    /** Позиція генератора зі списку підсвітки — зручність для рендера. */
    public static BlockPos posOf(GeneratorHighlightPacket.Entry entry) {
        return entry.pos();
    }

    /** Ростер усіх гравців матчу для tab-екрана — читається лише поки Tab утримується. */
    public static List<RosterSyncPacket.RosterEntry> roster() {
        return roster;
    }
}
