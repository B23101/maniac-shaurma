package com.log_to_kot.maniacmod.client.leaderboard;

import com.log_to_kot.maniacmod.leaderboard.LeaderboardCategory;
import com.log_to_kot.maniacmod.leaderboard.LeaderboardRow;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.leaderboard.LeaderboardRequestPacket;
import com.log_to_kot.maniacmod.net.s2c.leaderboard.LeaderboardOverviewPacket;
import com.log_to_kot.maniacmod.net.s2c.leaderboard.LeaderboardPagePacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.EnumMap;
import java.util.Map;

/**
 * Що сервер прислав клієнту з топів: огляд усіх таблиць і остання сторінка кожної категорії.
 * Екран лише читає звідси й просить через {@code request*}; жодних підписок — {@link #version()} росте на
 * кожну відповідь, екран бачить зміну порівнянням.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientLeaderboardState {

    /**
     * Накопичена таблиця категорії: рядки за місцем (приходять шматками, нові доповнюють старі) і становище гравця.
     * Екран гортає її як один довгий список.
     */
    public static final class Board {
        public int total;
        public int meRank;
        public long meValue;
        public String nearName = "";
        public long nearValue;
        public long receivedAtMs;
        private final java.util.TreeMap<Integer, LeaderboardRow> rows = new java.util.TreeMap<>();

        /** Рядок за місцем (від 1) або {@code null}, якщо ще не завантажено. */
        public LeaderboardRow row(int rank) { return rows.get(rank); }

        /** Найбільше завантажене місце; 0 — нічого нема. */
        public int loadedMax() { return rows.isEmpty() ? 0 : rows.lastKey(); }
    }

    /** Огляд: чемпіони й становище гравця в кожній таблиці. */
    public record Overview(int meCrowns, Map<LeaderboardCategory, LeaderboardOverviewPacket.Entry> byCategory,
                           long receivedAtMs) {}

    private static final Map<LeaderboardCategory, Board> BOARDS = new EnumMap<>(LeaderboardCategory.class);
    private static Overview overview;
    private static long version;

    private ClientLeaderboardState() {}

    // ── Відповіді сервера ────────────────────────────────────────────────

    public static void receivePage(LeaderboardPagePacket p) {
        LeaderboardCategory c = LeaderboardCategory.byOrdinal(p.category());
        if (c == null) return;   // сервер новіший за клієнта: невідому категорію просто ігноруємо
        Board b = BOARDS.computeIfAbsent(c, k -> new Board());
        b.total = p.total();
        b.meRank = p.meRank();
        b.meValue = p.meValue();
        b.nearName = p.nearName();
        b.nearValue = p.nearValue();
        b.receivedAtMs = System.currentTimeMillis();
        for (LeaderboardRow r : p.rows()) b.rows.put(r.rank(), r);
        b.rows.tailMap(p.total(), false).clear();   // таблиця могла стати коротшою
        version++;
    }

    public static void receiveOverview(LeaderboardOverviewPacket p) {
        Map<LeaderboardCategory, LeaderboardOverviewPacket.Entry> map = new EnumMap<>(LeaderboardCategory.class);
        for (LeaderboardOverviewPacket.Entry e : p.entries()) {
            LeaderboardCategory c = LeaderboardCategory.byOrdinal(e.category());
            if (c != null) map.put(c, e);
        }
        overview = new Overview(p.meCrowns(), map, System.currentTimeMillis());
        version++;
    }

    // ── Читання ──────────────────────────────────────────────────────────

    /** {@code null}, поки жодна відповідь не прийшла. */
    public static Board board(LeaderboardCategory c) { return BOARDS.get(c); }

    public static Overview overview() { return overview; }

    public static long version() { return version; }

    // ── Запити ───────────────────────────────────────────────────────────

    public static void requestOverview() {
        if (Minecraft.getInstance().getConnection() == null) return;
        ModNetwork.toServer(LeaderboardRequestPacket.overview());
    }

    public static void requestPage(LeaderboardCategory c, int offset, int limit) {
        if (Minecraft.getInstance().getConnection() == null) return;
        ModNetwork.toServer(new LeaderboardRequestPacket(c.ordinal(), offset, limit));
    }

    public static void clear() {
        BOARDS.clear();
        overview = null;
        version++;
        com.log_to_kot.maniacmod.client.screen.leaderboard.LeaderboardAvatar.clear();
    }
}
