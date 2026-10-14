package com.log_to_kot.maniacmod.intro;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.intro.IntroRevealPacket;
import com.log_to_kot.maniacmod.net.s2c.intro.IntroStartPacket;
import dev.shaurmalib.common.camera.replay.ReplayScript;
import dev.shaurmalib.common.lock.LockType;
import dev.shaurmalib.forge.camera.replay.ReplayDirector;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Серверна режисура кіно-вступу ({@code CINEMATIC} → {@code SCATTER}).
 *
 * ── Що робить сервер ──────────────────────────────────────────────────
 *  • вхід у CINEMATIC: кожному гравцю — блокування дій ({@link #LOCK_REASON}),
 *    {@link IntroStartPacket} і Replay-сценарій його ролі через
 *    {@link ReplayDirector} (він же веде чанки за камерою);
 *  • вихід із CINEMATIC (на ~75% фінального затемнення): Replay зупиняється,
 *    гравців повертає на місце — далі їх телепортує SCATTER;
 *  • вхід у ROLE_REVEAL: контроль повернено, {@link IntroRevealPacket} —
 *    клієнт розтемнює екран разом із пробудженням;
 *  • LOBBY/RESET: аварійне зняття всього (вихід посеред вступу, стоп матчу).
 *
 * Голос заглушують не тут: VoiceSilencePhaseModule вже глушить усіх у
 * CINEMATIC/SCATTER/ROLE_REVEAL. Тривалості — у {@link IntroTimeline}.
 *
 * ── Прев'ю ────────────────────────────────────────────────────────────
 * {@link #preview} програє той самий вступ ОДНОМУ гравцю в лобі
 * ({@code /maniac replay preview}) — щоб перевіряти точки без старту матчу.
 */
public final class IntroModule implements PhaseListener {

    public static final String LOCK_REASON = "intro";
    private static final LockType[] LOCKS = {
        LockType.MOVEMENT, LockType.ATTACK, LockType.USE_ITEM, LockType.BLOCK_INTERACT, LockType.CHAT
    };

    private final Supplier<MatchOrchestrator> match;
    /** Гравці матчу, що зараз у вступі. */
    private final Set<UUID> inMatchIntro = new HashSet<>();
    /** Прев'ю: гравець → скільки тіків минуло. */
    private final Map<UUID, Integer> previews = new HashMap<>();

    /**
     * Replay, що чекає, поки екран гравця стане чорним. {@link ReplayDirector#play}
     * одразу переносить «якір» чанків до камери, і клієнт скидає чанки навколо
     * гравця — якщо це відбувається посеред початкового затемнення, на екрані
     * видно світ без блоків (лише небо). Тому запускаємо Replay лише коли
     * затемнення завершилось ({@link IntroTimeline#CAMERA_START_MS}).
     */
    private static final class PendingReplay {
        final ReplayScript script;
        int ticksLeft;
        PendingReplay(ReplayScript script, int ticksLeft) {
            this.script = script;
            this.ticksLeft = ticksLeft;
        }
    }
    private final Map<UUID, PendingReplay> pendingReplay = new HashMap<>();

    public IntroModule(Supplier<MatchOrchestrator> match) {
        this.match = match;
    }

    @Override
    public String id() { return "intro"; }

    // ── Фази ─────────────────────────────────────────────────────────────

    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        switch (phase) {
            case CINEMATIC -> {
                if (!IntroTimeline.enabled()) return;
                for (ServerPlayer p : players) {
                    begin(p, match.get().isManiac(p.getUUID()));
                    inMatchIntro.add(p.getUUID());
                }
            }
            case ROLE_REVEAL -> {
                for (ServerPlayer p : players) {
                    if (inMatchIntro.remove(p.getUUID())) finish(p);
                }
                inMatchIntro.clear();
            }
            case LOBBY, RESET -> {
                for (ServerPlayer p : players) {
                    if (inMatchIntro.contains(p.getUUID())) abort(p);
                }
                inMatchIntro.clear();
            }
            default -> {}
        }
    }

    @Override
    public void onPhaseExit(GamePhase phase, List<ServerPlayer> players) {
        if (phase != GamePhase.CINEMATIC) return;
        // Replay закінчився: якір чанків знімається, гравця повертає на місце.
        // Клієнт сам завершує камеру за власним годинником (до цього моменту
        // екран уже майже чорний), тому Stop-пакет не шлемо.
        for (ServerPlayer p : players) {
            pendingReplay.remove(p.getUUID());
            if (inMatchIntro.contains(p.getUUID())) ReplayDirector.stop(p, true, false);
        }
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        // Хто вийшов посеред вступу — чистимо, щоб не лишилось замків на UUID.
        if (!inMatchIntro.isEmpty()) {
            Set<UUID> online = new HashSet<>();
            for (ServerPlayer p : players) online.add(p.getUUID());
            for (UUID id : new HashSet<>(inMatchIntro)) {
                if (!online.contains(id)) {
                    unlock(id);
                    inMatchIntro.remove(id);
                }
            }
        }
        if (!pendingReplay.isEmpty()) {
            for (ServerPlayer p : players) {
                PendingReplay pending = pendingReplay.get(p.getUUID());
                if (pending == null) continue;
                if (--pending.ticksLeft > 0) continue;
                pendingReplay.remove(p.getUUID());
                ReplayDirector.play(p, pending.script, 0);
            }
            // Гравець вийшов, не діждавшись старту, — не тримаємо запис.
            if (!pendingReplay.isEmpty()) {
                Set<UUID> online = new HashSet<>();
                for (ServerPlayer p : players) online.add(p.getUUID());
                pendingReplay.keySet().retainAll(online);
            }
        }
        if (previews.isEmpty()) return;
        for (ServerPlayer p : players) {
            Integer t = previews.get(p.getUUID());
            if (t == null) continue;
            t++;
            previews.put(p.getUUID(), t);
            if (t == IntroTimeline.fullCinematicTicks()) {
                ReplayDirector.stop(p, true, false);
            } else if (t >= IntroTimeline.fullCinematicTicks() + IntroTimeline.SCATTER_HOLD_TICKS) {
                previews.remove(p.getUUID());
                finish(p);
            }
        }
    }

    // ── Прев'ю ───────────────────────────────────────────────────────────

    /** Програє вступ одному гравцю. {@code false} — вже йде або йде матч. */
    public boolean preview(ServerPlayer player, boolean asManiac) {
        if (!match.get().phases().is(GamePhase.LOBBY)) return false;
        if (previews.containsKey(player.getUUID())) return false;
        previews.put(player.getUUID(), 0);
        begin(player, asManiac);
        return true;
    }

    // ── Крок вступу ──────────────────────────────────────────────────────

    private void begin(ServerPlayer p, boolean maniac) {
        for (LockType type : LOCKS) {
            ManiacMod.lib().interactionLockModule().lock(p.getUUID(), type, LOCK_REASON);
        }
        int window = IntroTimeline.windowMs();
        ReplayScript script = IntroReplayConfigs.scriptFor(maniac, window);
        if (script.isEmpty()) {
            ManiacMod.LOGGER.info("[intro] у {} немає Replay для ролі {} — лише затемнення й loader",
                IntroReplayConfigs.FILE_NAME, maniac ? "MANIAC" : "SURVIVOR");
        }
        ModNetwork.toPlayer(p, new IntroStartPacket(window, IntroTimeline.tipIntervalMs(), maniac, !script.isEmpty()));
        // Камера (і перенесення чанків за нею) стартує, коли екран уже повністю
        // чорний, — див. PendingReplay.
        int delayTicks = Math.max(1, (IntroTimeline.CAMERA_START_MS + 49) / 50);
        pendingReplay.put(p.getUUID(), new PendingReplay(script, delayTicks));
    }

    /** Контроль повернено + сигнал розтемнення. */
    private void finish(ServerPlayer p) {
        unlock(p.getUUID());
        ModNetwork.toPlayer(p, new IntroRevealPacket());
    }

    private void abort(ServerPlayer p) {
        pendingReplay.remove(p.getUUID());
        ReplayDirector.stop(p, true, true);
        unlock(p.getUUID());
        previews.remove(p.getUUID());
    }

    private static void unlock(UUID id) {
        for (LockType type : LOCKS) {
            ManiacMod.lib().interactionLockModule().unlock(id, type, LOCK_REASON);
        }
    }
}
