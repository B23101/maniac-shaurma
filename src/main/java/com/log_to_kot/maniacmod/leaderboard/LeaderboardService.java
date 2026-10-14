package com.log_to_kot.maniacmod.leaderboard;

import com.log_to_kot.maniacmod.progression.XpCurve;
import com.log_to_kot.maniacmod.progression.profile.PlayerProfile;
import com.log_to_kot.maniacmod.progression.profile.PlayerProfileStorage;
import com.mojang.authlib.properties.Property;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Серверне джерело всіх топів. Єдина правда — файли профілів ({@code PlayerProfileStorage}); тут лише
 * кеш у пам'яті, щоб запит екрана не читав сотні JSON.
 *
 * <h3>Життя кешу</h3>
 * <ul>
 *   <li>Перший запит читає всю теку профілів ({@link PlayerProfileStorage#loadAll()});</li>
 *   <li>кожне збереження профілю ({@link #onProfileSaved}) оновлює лише свого гравця;</li>
 *   <li>раз на {@value #RELOAD_MS} мс кеш відбудовується з диска (на випадок ручних правок файлів);</li>
 *   <li>{@link #clear()} на зупинці сервера — наступний світ у тій самій JVM не успадкує чужі топи.</li>
 * </ul>
 *
 * <p>Усе працює в головному потоці сервера (пакети й події Forge) — власної синхронізації нема.
 * Порожні профілі (без XP й матчів) у топи не потрапляють.</p>
 */
public final class LeaderboardService {

    private static final long RELOAD_MS = 10L * 60L * 1000L;
    /** Максимум рядків за один запит — захист від «дай усіх». */
    public static final int MAX_PAGE = 30;

    /** Знімок гравця для топів; незмінний після створення. */
    private record Entry(UUID id, String name, String skin, long lastSeenMs, int level, long totalXp,
                         int matches, int wins, String favManiac, long[] values) {}

    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();
    private static final Map<LeaderboardCategory, List<Entry>> SORTED = new EnumMap<>(LeaderboardCategory.class);
    private static final Map<LeaderboardCategory, Map<UUID, Integer>> RANKS = new EnumMap<>(LeaderboardCategory.class);
    private static final Map<UUID, Integer> CROWNS = new HashMap<>();
    private static boolean loaded;
    private static boolean dirty = true;
    private static long loadedAt;

    private LeaderboardService() {}

    // ── Життєвий цикл ────────────────────────────────────────────────────

    public static void clear() {
        ENTRIES.clear();
        SORTED.clear();
        RANKS.clear();
        CROWNS.clear();
        loaded = false;
        dirty = true;
    }

    /** Викликається з {@code PlayerProfileStorage.save}: оновлює одного гравця без перечитування диска. */
    public static void onProfileSaved(UUID id, PlayerProfile profile) {
        if (!loaded) return;   // перший запит усе одно прочитає теку, включно з цим файлом
        if (profile.hasActivity()) ENTRIES.put(id, snapshot(id, profile));
        else ENTRIES.remove(id);
        dirty = true;
    }

    /**
     * Записує в профіль те, чого там не було б само: нік, актуальний скін, час останньої активності.
     * Викликається перед збереженням профілю (вхід, вихід, кінець матчу).
     */
    public static void stamp(ServerPlayer player, PlayerProfile profile) {
        profile.playerName = player.getGameProfile().getName();
        profile.lastSeenMs = System.currentTimeMillis();
        for (Property prop : player.getGameProfile().getProperties().get("textures")) {
            String v = prop.getValue();
            if (v != null && v.length() <= LeaderboardRow.MAX_SKIN) profile.skinTexture = v;
            break;
        }
    }

    private static void ensureLoaded() {
        long now = System.currentTimeMillis();
        if (loaded && now - loadedAt < RELOAD_MS) return;
        ENTRIES.clear();
        for (Map.Entry<UUID, PlayerProfile> e : PlayerProfileStorage.loadAll().entrySet()) {
            PlayerProfile p = e.getValue();
            if (p.hasActivity()) ENTRIES.put(e.getKey(), snapshot(e.getKey(), p));
        }
        loaded = true;
        loadedAt = now;
        dirty = true;
    }

    private static Entry snapshot(UUID id, PlayerProfile p) {
        long[] values = new long[LeaderboardCategory.count()];
        for (LeaderboardCategory c : LeaderboardCategory.values()) values[c.ordinal()] = c.valueOf(p);
        String name = p.playerName == null || p.playerName.isEmpty() ? "???" : p.playerName;
        return new Entry(id, name, p.skinTexture == null ? "" : p.skinTexture, p.lastSeenMs,
            XpCurve.levelOf(p.totalXp()), p.totalXp(), p.totalMatches(), p.totalWins(),
            p.favouriteManiac(), values);
    }

    private static void rebuildIfDirty() {
        if (!dirty) return;
        SORTED.clear();
        RANKS.clear();
        CROWNS.clear();
        for (LeaderboardCategory c : LeaderboardCategory.values()) {
            int idx = c.ordinal();
            List<Entry> list = new ArrayList<>();
            for (Entry e : ENTRIES.values()) if (e.values()[idx] > 0L) list.add(e);
            // Більше значення — вище; при рівності — вищий загальний XP, далі нік (стабільний порядок).
            list.sort(Comparator.<Entry>comparingLong(e -> -e.values()[idx])
                .thenComparingLong(e -> -e.totalXp())
                .thenComparing(e -> e.name().toLowerCase(java.util.Locale.ROOT))
                .thenComparing(Entry::id));
            SORTED.put(c, list);
            Map<UUID, Integer> ranks = new HashMap<>();
            for (int i = 0; i < list.size(); i++) ranks.put(list.get(i).id(), i + 1);
            RANKS.put(c, ranks);
            if (!list.isEmpty()) CROWNS.merge(list.get(0).id(), 1, Integer::sum);
        }
        dirty = false;
    }

    // ── Запити ───────────────────────────────────────────────────────────

    /** Скільки гравців у таблиці. */
    public static int total(LeaderboardCategory c) {
        ensureLoaded();
        rebuildIfDirty();
        return SORTED.get(c).size();
    }

    /** Сторінка таблиці: рядки {@code [offset, offset+limit)}. */
    public static List<LeaderboardRow> page(LeaderboardCategory c, int offset, int limit) {
        ensureLoaded();
        rebuildIfDirty();
        List<Entry> list = SORTED.get(c);
        int from = Math.max(0, Math.min(offset, list.size()));
        int to = Math.min(list.size(), from + Math.max(0, Math.min(limit, MAX_PAGE)));
        List<LeaderboardRow> out = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) out.add(row(c, list.get(i), i + 1));
        return out;
    }

    /** Місце гравця; {@code 0} — не в таблиці. */
    public static int rankOf(LeaderboardCategory c, UUID id) {
        ensureLoaded();
        rebuildIfDirty();
        return RANKS.get(c).getOrDefault(id, 0);
    }

    public static long valueOf(LeaderboardCategory c, UUID id) {
        ensureLoaded();
        Entry e = ENTRIES.get(id);
        return e == null ? 0L : e.values()[c.ordinal()];
    }

    public static int crownsOf(UUID id) {
        ensureLoaded();
        rebuildIfDirty();
        return CROWNS.getOrDefault(id, 0);
    }

    /** Чемпіон таблиці або {@code null}, якщо вона порожня. */
    public static LeaderboardRow champion(LeaderboardCategory c) {
        ensureLoaded();
        rebuildIfDirty();
        List<Entry> list = SORTED.get(c);
        return list.isEmpty() ? null : row(c, list.get(0), 1);
    }

    /** Сусід, якого гравцеві треба наздогнати (місцем вище) — або, якщо він №1, того, хто дихає в потилицю. */
    public static LeaderboardRow neighbour(LeaderboardCategory c, UUID id) {
        ensureLoaded();
        rebuildIfDirty();
        int rank = RANKS.get(c).getOrDefault(id, 0);
        List<Entry> list = SORTED.get(c);
        if (rank == 0) return list.isEmpty() ? null : row(c, list.get(list.size() - 1), list.size());
        int idx = rank == 1 ? 1 : rank - 2;
        return idx < list.size() ? row(c, list.get(idx), idx + 1) : null;
    }

    private static LeaderboardRow row(LeaderboardCategory c, Entry e, int rank) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        boolean online = server != null && server.getPlayerList().getPlayer(e.id()) != null;
        return new LeaderboardRow(rank, e.id(), e.name(), e.values()[c.ordinal()], e.level(), e.matches(), e.wins(),
            e.favManiac(), CROWNS.getOrDefault(e.id(), 0), online, e.lastSeenMs(), e.skin());
    }
}
