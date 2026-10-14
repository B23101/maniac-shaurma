package com.log_to_kot.maniacmod.victory;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.spawn.SpawnPoint;
import com.log_to_kot.maniacmod.spawn.SpawnPointKind;
import dev.shaurmalib.common.camera.replay.ReplayClip;
import dev.shaurmalib.common.camera.replay.ReplayScript;
import dev.shaurmalib.common.lock.LockType;
import dev.shaurmalib.forge.camera.replay.ReplayDirector;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Freecam екрана підсумків: на фазі {@link GamePhase#ENDING} камера кожного гравця стає на точку
 * {@link SpawnPointKind#STATS_CAMERA}, і за напівпрозорим інтерфейсом статистики видно світ звідти.
 *
 * <p>Механіка та сама, що в сцені перемоги ({@link VictorySceneModule}): гравець у SPECTATOR із
 * замками руху, камеру веде {@code ReplayDirector} (один кліп — утримання точки), тіло ReplayDirector
 * тримає біля чанка камери, тож світ там вантажиться. Точки нема — модуль нічого не робить, екран
 * малюється поверх того, що бачить гравець.</p>
 *
 * <p>Старт із невеликою затримкою, щоб не конфліктувати з перевіркою «усіх повернуто в лобі»
 * після сцени перемоги ({@code VictorySceneModule#guardLobby}). Камера тримається, доки гравець
 * сам не закриє екран статистики ({@link #release}); матч тим часом скидається як завжди.
 * Лобі-телепорт на RESET → LOBBY для таких гравців пропускається (див. {@code MatchStartCoordinator}),
 * він відбувається в момент закриття екрана.</p>
 */
public final class StatsCameraModule implements PhaseListener {

    public static final String LOCK_REASON = "stats_camera";
    private static final LockType[] LOCKS = {
        LockType.MOVEMENT, LockType.ATTACK, LockType.USE_ITEM, LockType.BLOCK_INTERACT
    };
    /** Через скільки тіків ENDING вмикаємо freecam (після lobbyGuard сцени перемоги — 10 тіків). */
    private static final long START_AFTER_TICKS = 14L;
    /** Запас утримання понад {@code endingSeconds} (вертоліт може затримати ENDING). */
    private static final int HOLD_PAD_SECONDS = 60;

    private final Supplier<MatchOrchestrator> match;
    private final Set<UUID> active = new HashSet<>();
    private final Map<UUID, GameType> previousMode = new HashMap<>();
    private boolean started;
    private long startTick;
    private long tickCounter;
    private double[] eye;   // x,y,z,yaw,pitch

    public StatsCameraModule(Supplier<MatchOrchestrator> match) {
        this.match = match;
    }

    @Override
    public String id() { return "stats_camera"; }

    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        if (phase == GamePhase.ENDING) {
            started = false;
        } else if (phase == GamePhase.CINEMATIC) {
            // Новий матч: хто ще дивиться статистику минулого — повертаємо в лобі.
            releaseAll(players);
        }
    }

    /**
     * Камера НЕ знімається ні на виході з ENDING, ні на RESET/LOBBY: матч скидається у фоні,
     * а гравець дивиться статистику, поки сам не закриє екран (тоді клієнт шле
     * {@code StatsViewClosePacket} → {@link #release}).
     */
    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticks) {
        tickCounter++;
        if (phase == GamePhase.ENDING && !started && ticks >= START_AFTER_TICKS) {
            start(players, tickCounter);
            return;
        }
        if (!active.isEmpty()) guard(players, tickCounter);
    }

    /** Гравець зараз дивиться статистику через freecam (його не можна чіпати лобі-логікою). */
    public boolean isViewing(UUID id) {
        return active.contains(id);
    }

    /** Гравець закрив екран статистики: зняти камеру й повернути в лобі. */
    public void release(ServerPlayer p) {
        if (p == null || !active.remove(p.getUUID())) return;
        ReplayDirector.stop(p, false, true);
        unlock(p.getUUID());
        GameType prev = previousMode.remove(p.getUUID());
        if (prev != null) p.setGameMode(prev);
        match.get().returnToLobby(p);
        if (active.isEmpty()) eye = null;
    }

    public void releaseAll(List<ServerPlayer> players) {
        for (ServerPlayer p : players) release(p);
        for (UUID id : new HashSet<>(active)) unlock(id);   // хто вийшов з гри
        active.clear();
        previousMode.clear();
        eye = null;
    }

    private SpawnPoint cameraPoint() {
        for (SpawnPoint p : match.get().spawnPoints()) {
            if (p.kind() == SpawnPointKind.STATS_CAMERA) return p;
        }
        return null;
    }

    private void start(List<ServerPlayer> players, long ticks) {
        started = true;
        startTick = ticks;
        SpawnPoint cam = cameraPoint();
        if (cam == null || players.isEmpty()) return;

        eye = new double[] {cam.exactX(), cam.exactY(), cam.exactZ(), cam.yaw(), cam.pitch()};
        int holdMs = (ManiacConfigs.get(ConfigSchema.ENDING_SECONDS) + HOLD_PAD_SECONDS) * 1000;
        ReplayClip hold = ReplayClip.builder("stats_hold")
            .point(cam.exactX(), cam.exactY(), cam.exactZ(), cam.yaw(), cam.pitch())
            .cutAtMs(holdMs)
            .build();
        ReplayScript script = ReplayScript.of(0L, hold);

        for (ServerPlayer p : players) {
            if (!active.add(p.getUUID())) continue;
            previousMode.put(p.getUUID(), p.gameMode.getGameModeForPlayer());
            p.setGameMode(GameType.SPECTATOR);
            lock(p.getUUID());
            ReplayDirector.play(p, script, 300);
        }
    }

    /** Гейммод повертається в SPECTATOR; збиту камеру замінює тримання тіла біля точки. */
    private void guard(List<ServerPlayer> players, long ticks) {
        if (eye == null) return;
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer p : players) online.add(p.getUUID());
        active.removeIf(id -> {
            if (online.contains(id)) return false;
            unlock(id);
            previousMode.remove(id);
            return true;
        });
        for (ServerPlayer p : players) {
            if (!active.contains(p.getUUID())) continue;
            if (p.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) p.setGameMode(GameType.SPECTATOR);
            if (ticks > startTick + 8 && !ReplayDirector.isRunning(p.getUUID())
                && p.distanceToSqr(eye[0], eye[1], eye[2]) > 4.0) {
                p.teleportTo(p.serverLevel(), eye[0], eye[1] - 1.6, eye[2], (float) eye[3], (float) eye[4]);
            }
        }
    }

    private static void lock(UUID id) {
        for (LockType type : LOCKS) ManiacMod.lib().interactionLockModule().lock(id, type, LOCK_REASON);
    }

    private static void unlock(UUID id) {
        for (LockType type : LOCKS) ManiacMod.lib().interactionLockModule().unlock(id, type, LOCK_REASON);
    }
}
