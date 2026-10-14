package com.log_to_kot.maniacmod.victory;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.victory.VictoryEndPacket;
import com.log_to_kot.maniacmod.net.s2c.victory.VictoryStartPacket;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import com.log_to_kot.maniacmod.spawn.SpawnPoint;
import com.log_to_kot.maniacmod.spawn.SpawnPointKind;
import com.mojang.authlib.properties.Property;
import dev.shaurmalib.common.camera.replay.ReplayClip;
import dev.shaurmalib.common.camera.replay.ReplayInterpolation;
import dev.shaurmalib.common.camera.replay.ReplayScript;
import dev.shaurmalib.common.lock.LockType;
import dev.shaurmalib.forge.camera.replay.ReplayDirector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Серверна режисура сцени перемоги ({@link GamePhase#VICTORY_SCENE}) — і маніяка, і гравців.
 *
 * <h3>Дві сцени</h3>
 * <ul>
 *   <li><b>Перемога маніяка</b> ({@code !survivorsWon}): гачки з тілами + маніяк, камери
 *       {@code VICTORY_CAMERA}. Хронологія нижче.</li>
 *   <li><b>Перемога гравців</b> ({@code survivorsWon}): на точках {@code VICTORY_PLAYER} (до 10)
 *       танцюють ті, хто втік, на точках {@code VICTORY_GHOST} (до 10) висять у повітрі привиди
 *       тих, хто не вижив; камера — {@code VICTORY_PLAYERS_CAMERA}, а якщо її не розмічено —
 *       {@code VICTORY_CAMERA}. Хронологія та сама.</li>
 * </ul>
 *
 * <h3>Хронологія (тіки від входу у фазу)</h3>
 * <pre>
 *  0 ........ FADE      клієнти затемнюються (VictoryStartPacket)
 *  FADE ..... prepare   усіх у спектатор, +lift блоків угору, замки; чанки точок примусово;
 *                       спавн гачків із опудалами й маніяків; ReplayDirector.play (камера
 *                       стартує через LOAD сек — чорний екран, чанки вантажаться)
 *  +LOAD .... камера їде від точки A до B (MOVE сек)
 *  +MOVE .... клієнт показує титул (TITLE сек), потім затемнюється (FADE_OUT)
 *  +TITLE+FADE_OUT  finish: Replay стоп, усіх у лобі, прибирання, VictoryEndPacket, isFinished
 * </pre>
 * Далі оркестратор іде в ENDING (екран статистики).
 *
 * <h3>Захист</h3>
 * Гравці в спектаторі, з замками, тіло прив'язане ReplayDirector до чанка камери (над світом).
 * Щотіку: гейммод повертається в SPECTATOR, а якщо Replay гравця зник (клієнт збив камеру) —
 * тіло переноситься на кінцеву точку камери й тримається там. Хто вийшов — прибирається зі
 * списку; не лишилось нікого — сцена завершується одразу. Сутності-сироти (рестарт) знищує сама
 * сутність, бо {@link #owns} для них хибне.
 */
public final class VictorySceneModule implements PhaseListener {

    public static final String LOCK_REASON = "victory_scene";
    private static final LockType[] LOCKS = {
        LockType.MOVEMENT, LockType.ATTACK, LockType.USE_ITEM, LockType.BLOCK_INTERACT, LockType.CHAT
    };
    private static final int FADE_IN_MS = 600;
    private static final int FADE_OUT_MS = 1200;
    private static final int HOLD_PAD_MS = 1500;
    /** Стеля чорного екрана перед камерою: у старих game.yml лишилось 3 с — це задовго. */
    private static final int MAX_LOAD_MS = 1200;
    /** Скільки маніяків у матчі (MatchContext тримає одного). Рівно стільки й малюємо в сцені. */
    private static final int MANIACS_IN_MATCH = 1;
    /** Скільки тіків після кінця сцени стежимо, що гравці справді опинились у лобі. */
    private static final long LOBBY_GUARD_TICKS = 10;

    private final Supplier<MatchOrchestrator> match;

    private final Set<UUID> participants = new HashSet<>();
    private final Map<UUID, GameType> previousMode = new HashMap<>();
    private final Set<UUID> spawned = new HashSet<>();
    private final List<Entity> spawnedEntities = new ArrayList<>();
    private final Set<Long> forcedByUs = new HashSet<>();
    /** Хто щойно пройшов finish(): перевіряємо, що телепорт у лобі не перебито. */
    private final Set<UUID> lobbyGuard = new HashSet<>();
    private ServerLevel level;

    private boolean finished = true;
    private boolean prepared;
    private long prepareTick;
    private long finishTick;
    private double[] finalEye;     // x,y,z,yaw,pitch
    /** Яку сцену граємо зараз (визначається на begin за правилом перемоги). */
    private boolean survivorsScene;
    /** Скільки гравців/привидів сцена перемоги гравців малює максимум (стільки ж точок). */
    private static final int MAX_PLAYERS_ON_SCENE = 10;

    public VictorySceneModule(Supplier<MatchOrchestrator> match) {
        this.match = match;
    }

    @Override
    public String id() { return "victory_scene"; }

    // ── Запити оркестратора ──────────────────────────────────────────────

    public boolean isFinished() { return finished; }

    /** Сутність належить поточній сцені (інакше вона сирота й сама себе видаляє). */
    public boolean owns(Entity entity) { return spawned.contains(entity.getUUID()); }

    /**
     * Чи можна грати сцену: ввімкнено й розмічено. Маніяк — ≥2 точки камери і ≥1 гачок; гравці —
     * ≥2 точки камери (власні або камери маніяка) і хоч одна точка гравця/привида. Яка саме сцена —
     * вирішує {@code survivorsWon()} (на момент виклику результат уже відомий).
     */
    public boolean canPlay(List<ServerPlayer> players) {
        if (!ManiacConfigs.get(ConfigSchema.VICTORY_SCENE_ENABLED)) return false;
        if (players.isEmpty()) return false;
        if (match.get().survivorsWon()) {
            return cameras(true).size() >= 2
                && (!points(SpawnPointKind.VICTORY_PLAYER).isEmpty()
                    || !points(SpawnPointKind.VICTORY_GHOST).isEmpty());
        }
        return points(SpawnPointKind.VICTORY_CAMERA).size() >= 2
            && !points(SpawnPointKind.VICTORY_HOOK).isEmpty();
    }

    /** Камери потрібної сцени: гравці — власна пара, інакше (або для маніяка) — загальна. */
    private List<SpawnPoint> cameras(boolean forSurvivors) {
        if (forSurvivors) {
            List<SpawnPoint> own = points(SpawnPointKind.VICTORY_PLAYERS_CAMERA);
            if (own.size() >= 2) return own;
        }
        return points(SpawnPointKind.VICTORY_CAMERA);
    }

    private List<SpawnPoint> points(SpawnPointKind kind) {
        List<SpawnPoint> out = new ArrayList<>();
        for (SpawnPoint p : match.get().spawnPoints()) if (p.kind() == kind) out.add(p);
        return out;
    }

    private static int cfg(com.log_to_kot.maniacmod.config.ConfigKey<Integer> key) {
        return ManiacConfigs.get(key);
    }

    // ── Фази ─────────────────────────────────────────────────────────────

    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        if (phase == GamePhase.VICTORY_SCENE) begin(players);
        else if ((phase == GamePhase.LOBBY || phase == GamePhase.RESET) && !finished) abort(players);
    }

    @Override
    public void onPhaseExit(GamePhase phase, List<ServerPlayer> players) {
        // Вихід із сцени не з нашої ініціативи (стоп командою) — прибрати все.
        if (phase == GamePhase.VICTORY_SCENE && !finished) abort(players);
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticks) {
        if (phase == GamePhase.ENDING && !lobbyGuard.isEmpty()) {
            guardLobby(players, ticks);
            return;
        }
        if (phase != GamePhase.VICTORY_SCENE || finished) return;

        // Хто вийшов — прибрати з обліку.
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer p : players) online.add(p.getUUID());
        for (UUID id : new HashSet<>(participants)) {
            if (!online.contains(id)) {
                unlock(id);
                participants.remove(id);
                previousMode.remove(id);
            }
        }
        if (participants.isEmpty()) {
            finish(players);
            return;
        }

        if (!prepared && ticks >= msToTicks(FADE_IN_MS)) {
            prepare(players);
            prepareTick = ticks;
            finishTick = ticks + msToTicks(loadMs())
                + msToTicks(cfg(ConfigSchema.VICTORY_SCENE_CAMERA_SECONDS) * 1000)
                + msToTicks(cfg(ConfigSchema.VICTORY_SCENE_TITLE_SECONDS) * 1000)
                + msToTicks(FADE_OUT_MS);
            return;
        }
        if (!prepared) return;

        guard(players, ticks);
        if (ticks >= finishTick) finish(players);
    }

    /** Скільки камера чекає під чорним екраном (чанки й сутності встигають прогрузитись). */
    private static int loadMs() {
        return Math.min(cfg(ConfigSchema.VICTORY_SCENE_LOAD_SECONDS) * 1000, MAX_LOAD_MS);
    }

    private static long msToTicks(long ms) { return (ms + 49) / 50; }

    // ── Старт ────────────────────────────────────────────────────────────

    private void begin(List<ServerPlayer> players) {
        finished = false;
        prepared = false;
        survivorsScene = match.get().survivorsWon();
        participants.clear();
        previousMode.clear();
        lobbyGuard.clear();
        for (ServerPlayer p : players) participants.add(p.getUUID());
        level = players.isEmpty() ? null : players.get(0).serverLevel();

        int load = loadMs();
        int move = cfg(ConfigSchema.VICTORY_SCENE_CAMERA_SECONDS) * 1000;
        int title = cfg(ConfigSchema.VICTORY_SCENE_TITLE_SECONDS) * 1000;
        VictoryStartPacket packet = new VictoryStartPacket(FADE_IN_MS, load, move, title, FADE_OUT_MS, survivorsScene);
        for (ServerPlayer p : players) {
            lock(p.getUUID());
            ModNetwork.toPlayer(p, packet);
        }
    }

    /** Екран уже чорний: збираємо сцену й запускаємо камеру. */
    private void prepare(List<ServerPlayer> players) {
        prepared = true;
        List<SpawnPoint> cams = cameras(survivorsScene);
        SpawnPoint a = cams.get(0);
        SpawnPoint b = cams.get(1);
        finalEye = new double[] {b.exactX(), b.exactY(), b.exactZ(), b.yaw(), b.pitch()};

        // 1) чанки сцени — примусово, щоб сутності мали куди з'явитись
        List<SpawnPoint> hooks = survivorsScene ? List.of() : points(SpawnPointKind.VICTORY_HOOK);
        List<SpawnPoint> maniacs = survivorsScene ? List.of() : points(SpawnPointKind.VICTORY_MANIAC);
        List<SpawnPoint> alivePoints = survivorsScene ? points(SpawnPointKind.VICTORY_PLAYER) : List.of();
        List<SpawnPoint> ghostPoints = survivorsScene ? points(SpawnPointKind.VICTORY_GHOST) : List.of();
        for (SpawnPoint p : hooks) forceAround(p);
        for (SpawnPoint p : maniacs) forceAround(p);
        for (SpawnPoint p : alivePoints) forceAround(p);
        for (SpawnPoint p : ghostPoints) forceAround(p);
        for (SpawnPoint p : cams) forceAround(p);

        // 2) гравці: спектатор, підйом, замки
        int lift = cfg(ConfigSchema.VICTORY_SCENE_LIFT_BLOCKS);
        for (ServerPlayer p : players) {
            if (!participants.contains(p.getUUID())) continue;
            previousMode.put(p.getUUID(), p.gameMode.getGameModeForPlayer());
            p.setGameMode(GameType.SPECTATOR);
            p.teleportTo(p.serverLevel(), p.getX(), p.getY() + lift, p.getZ(), p.getYRot(), p.getXRot());
        }

        // 3) сутності
        if (survivorsScene) {
            spawnPlayers(alivePoints, ghostPoints);
        } else {
            spawnHooks(hooks);
            spawnManiacs(maniacs);
        }

        // 4) камера
        int load = loadMs();
        int move = cfg(ConfigSchema.VICTORY_SCENE_CAMERA_SECONDS) * 1000;
        int title = cfg(ConfigSchema.VICTORY_SCENE_TITLE_SECONDS) * 1000;
        float shake = cfg(ConfigSchema.VICTORY_SCENE_SHAKE_PERCENT) / 20f;
        ReplayClip moveClip = ReplayClip.builder("victory_move")
            .point(a.exactX(), a.exactY(), a.exactZ(), a.yaw(), a.pitch(), move)
            .point(b.exactX(), b.exactY(), b.exactZ(), b.yaw(), b.pitch())
            .interpolation(ReplayInterpolation.SMOOTH)
            .shake(shake)
            .build();
        ReplayClip holdClip = ReplayClip.builder("victory_hold")
            .point(b.exactX(), b.exactY(), b.exactZ(), b.yaw(), b.pitch())
            .cutAtMs(title + FADE_OUT_MS + HOLD_PAD_MS)
            .shake(shake * 0.5f)
            .build();
        ReplayScript script = ReplayScript.of(0L, moveClip, holdClip);
        for (ServerPlayer p : players) {
            if (participants.contains(p.getUUID())) ReplayDirector.play(p, script, load);
        }
    }

    private void forceAround(SpawnPoint p) {
        if (level == null) return;
        int cx = p.pos().getX() >> 4;
        int cz = p.pos().getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = ChunkPos.asLong(cx + dx, cz + dz);
                if (level.getForcedChunks().contains(key) || forcedByUs.contains(key)) continue;
                level.setChunkForced(cx + dx, cz + dz, true);
                forcedByUs.add(key);
            }
        }
    }

    private void spawnHooks(List<SpawnPoint> hooks) {
        if (level == null) return;
        MatchOrchestrator m = match.get();
        List<UUID> victims = new ArrayList<>(m.survivorIds());
        for (UUID id : m.escapedSurvivorIds()) if (!victims.contains(id)) victims.add(id);
        for (UUID id : m.eliminatedSurvivorIds()) if (!victims.contains(id)) victims.add(id);
        Collections.shuffle(victims, new Random(victims.hashCode() * 31L + hooks.size()));

        boolean emptyHooks = ManiacConfigs.get(ConfigSchema.VICTORY_SCENE_EMPTY_HOOKS);
        List<SpawnPoint> order = new ArrayList<>(hooks);
        Collections.shuffle(order, new Random(order.size() * 17L + victims.size()));
        for (int i = 0; i < order.size(); i++) {
            SpawnPoint p = order.get(i);
            UUID victim = i < victims.size() ? victims.get(i) : null;
            if (victim == null && !emptyHooks) continue;
            String texture = "";
            String signature = "";
            if (victim != null) {
                ServerPlayer online = m.onlinePlayer(victim);
                if (online != null) {
                    for (Property prop : online.getGameProfile().getProperties().get("textures")) {
                        texture = prop.getValue();
                        signature = prop.getSignature() == null ? "" : prop.getSignature();
                        break;
                    }
                }
            }
            VictoryHookEntity e = VictoryHookEntity.spawn(ModEntityTypes.VICTORY_HOOK.get(), level,
                p.exactX(), p.exactY(), p.exactZ(), p.yaw(), victim, texture, signature, i);
            track(e);
        }
    }

    /**
     * БАГ: маніяки спавнились на КОЖНІЙ точці {@code VICTORY_MANIAC} — при двох розмічених точках
     * у кадрі було два маніяки, хоча в матчі він один. Тепер кількість сутностей = кількість
     * маніяків матчу (зараз завжди 1), точка обирається випадково з розмічених; зайві точки
     * просто лишаються запасними.
     */
    private void spawnManiacs(List<SpawnPoint> maniacs) {
        if (level == null || maniacs.isEmpty()) return;
        var archetype = match.get().maniacArchetype();
        String id = archetype == null ? "" : archetype.id();
        int count = Math.min(MANIACS_IN_MATCH, maniacs.size());
        List<SpawnPoint> pool = new ArrayList<>(maniacs);
        Collections.shuffle(pool, new Random());
        for (int i = 0; i < count; i++) {
            SpawnPoint p = pool.get(i);
            track(VictoryManiacEntity.spawn(ModEntityTypes.VICTORY_MANIAC.get(), level,
                p.exactX(), p.exactY(), p.exactZ(), p.yaw(), id));
        }
    }

    /**
     * Сцена перемоги гравців. «Живі» — ті, хто втік; «привиди» — решта виживих матчу (вибулі й ті,
     * кого не врятували). Маніяка на сцені немає. Кожній групі — не більше точок, ніж розмічено
     * (і не більше {@link #MAX_PLAYERS_ON_SCENE}); порядок точок випадковий, щоб склад не стояв
     * однаково щоразу. Скін — із профілю онлайн-гравця, інакше за UUID.
     */
    private void spawnPlayers(List<SpawnPoint> alivePoints, List<SpawnPoint> ghostPoints) {
        if (level == null) return;
        MatchOrchestrator m = match.get();
        List<UUID> alive = new ArrayList<>(m.escapedSurvivorIds());
        List<UUID> dead = new ArrayList<>();
        for (UUID id : m.eliminatedSurvivorIds()) if (!alive.contains(id) && !dead.contains(id)) dead.add(id);
        for (UUID id : m.survivorIds()) if (!alive.contains(id) && !dead.contains(id)) dead.add(id);
        placeGroup(alive, alivePoints, false);
        placeGroup(dead, ghostPoints, true);
    }

    private void placeGroup(List<UUID> who, List<SpawnPoint> points, boolean ghost) {
        if (who.isEmpty() || points.isEmpty()) return;
        MatchOrchestrator m = match.get();
        List<SpawnPoint> order = new ArrayList<>(points);
        Collections.shuffle(order, new Random(order.size() * 31L + who.size() + (ghost ? 7 : 0)));
        int count = Math.min(Math.min(who.size(), order.size()), MAX_PLAYERS_ON_SCENE);
        for (int i = 0; i < count; i++) {
            UUID id = who.get(i);
            SpawnPoint p = order.get(i);
            String texture = "";
            String signature = "";
            ServerPlayer online = m.onlinePlayer(id);
            if (online != null) {
                for (Property prop : online.getGameProfile().getProperties().get("textures")) {
                    texture = prop.getValue();
                    signature = prop.getSignature() == null ? "" : prop.getSignature();
                    break;
                }
            }
            track(VictoryPlayerEntity.spawn(ModEntityTypes.VICTORY_PLAYER.get(), level,
                p.exactX(), p.exactY(), p.exactZ(), p.yaw(), ghost, i, id, texture, signature));
        }
    }

    private void track(Entity e) {
        if (e == null) return;
        spawned.add(e.getUUID());
        spawnedEntities.add(e);
    }

    // ── Захист ───────────────────────────────────────────────────────────

    private void guard(List<ServerPlayer> players, long ticks) {
        long camStart = prepareTick + msToTicks(cfg(ConfigSchema.VICTORY_SCENE_LOAD_SECONDS) * 1000L);
        for (ServerPlayer p : players) {
            if (!participants.contains(p.getUUID())) continue;
            if (p.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) p.setGameMode(GameType.SPECTATOR);
            if (ticks > prepareTick + 2 && !ReplayDirector.isRunning(p.getUUID()) && finalEye != null) {
                // Камеру збито/зупинено — тримаємо тіло на кінцевій точці, далеко не втече.
                if (p.distanceToSqr(finalEye[0], finalEye[1], finalEye[2]) > 4.0) {
                    p.teleportTo(p.serverLevel(), finalEye[0], finalEye[1] - 1.6, finalEye[2],
                        (float) finalEye[3], (float) finalEye[4]);
                }
            }
        }
    }

    // ── Завершення ───────────────────────────────────────────────────────

    private void finish(List<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            if (!participants.contains(p.getUUID())) continue;
            ReplayDirector.stop(p, false, true);
        }
        cleanup(players);
        for (ServerPlayer p : players) {
            if (!participants.contains(p.getUUID())) continue;
            match.get().returnToLobby(p);
            ModNetwork.toPlayer(p, new VictoryEndPacket());
        }
        lobbyGuard.clear();
        lobbyGuard.addAll(participants);
        participants.clear();
        finished = true;
    }

    /**
     * БАГ: після сцени гравців не завжди перекидало в лобі одразу — телепорт у finish() міг
     * бути перебитий зупинкою камери (ReplayDirector.stop повертає тіло / гейммод), і гравець
     * лишався над картою аж до наступного скидання. Кілька тіків після finish() перевіряємо:
     * хто далі за кілька блоків від лобі або досі в спектаторі — відправляємо в лобі ще раз.
     */
    private void guardLobby(List<ServerPlayer> players, long ticks) {
        if (ticks > LOBBY_GUARD_TICKS) {
            lobbyGuard.clear();
            return;
        }
        var cfg = com.log_to_kot.maniacmod.config.MapPointConfigs.snapshot();
        for (ServerPlayer p : players) {
            if (!lobbyGuard.contains(p.getUUID())) continue;
            boolean far = p.distanceToSqr(cfg.lobbyX(), cfg.lobbyY(), cfg.lobbyZ()) > 36.0;
            boolean spectator = p.gameMode.getGameModeForPlayer() == GameType.SPECTATOR;
            if (far || spectator) {
                match.get().returnToLobby(p);
            }
        }
    }

    private void abort(List<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            if (participants.contains(p.getUUID())) ReplayDirector.stop(p, false, true);
        }
        cleanup(players);
        for (ServerPlayer p : players) {
            if (participants.contains(p.getUUID())) ModNetwork.toPlayer(p, new VictoryEndPacket());
        }
        participants.clear();
        finished = true;
    }

    private void cleanup(Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            if (!participants.contains(p.getUUID())) continue;
            unlock(p.getUUID());
            GameType prev = previousMode.get(p.getUUID());
            if (prev != null) p.setGameMode(prev);
        }
        previousMode.clear();
        spawned.clear();
        for (Entity e : spawnedEntities) if (e != null && !e.isRemoved()) e.discard();
        spawnedEntities.clear();
        if (level != null) {
            for (long key : forcedByUs) {
                level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
            }
        }
        forcedByUs.clear();
        finalEye = null;
    }

    private static void lock(UUID id) {
        for (LockType type : LOCKS) {
            ManiacMod.lib().interactionLockModule().lock(id, type, LOCK_REASON);
        }
    }

    private static void unlock(UUID id) {
        for (LockType type : LOCKS) {
            ManiacMod.lib().interactionLockModule().unlock(id, type, LOCK_REASON);
        }
    }
}
