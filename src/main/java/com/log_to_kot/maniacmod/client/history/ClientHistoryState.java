package com.log_to_kot.maniacmod.client.history;

import com.log_to_kot.maniacmod.history.MatchBrief;
import com.log_to_kot.maniacmod.history.MatchRecord;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.history.HistoryDetailRequestPacket;
import com.log_to_kot.maniacmod.net.c2s.history.HistoryListRequestPacket;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Що сервер прислав клієнту з історії: останні списки (адмінський і по гравцях) і відкриті матчі.
 * Екрани лише читають звідси й запитують через {@code request*}; сервер усе одно сам перевіряє права.
 *
 * <p>{@link #version()} росте на кожну відповідь — екран бачить зміну без підписок.</p>
 */
public final class ClientHistoryState {

    /** Сторінка списку. {@code target == null} — адмінський список усіх матчів. */
    public record ListView(UUID target, int total, int offset, List<MatchBrief> items, long receivedAtMs) {}

    /** Відповідь на запит матчу. {@code record == null} — не знайдено або немає доступу. */
    public record Detail(String id, MatchRecord record, long receivedAtMs) {}

    private static final Map<String, ListView> LISTS = new HashMap<>();
    private static final Map<String, Detail> DETAILS = new HashMap<>();
    private static long version;

    private ClientHistoryState() {}

    private static String key(UUID target) {
        return target == null ? "all" : target.toString();
    }

    // ── Відповіді сервера ────────────────────────────────────────────────

    public static void receiveList(UUID target, int total, int offset, List<MatchBrief> items) {
        LISTS.put(key(target), new ListView(target, total, offset, items, System.currentTimeMillis()));
        version++;
    }

    public static void receiveDetail(String id, MatchRecord record) {
        if (DETAILS.size() > 64) DETAILS.clear();
        DETAILS.put(id, new Detail(id, record, System.currentTimeMillis()));
        version++;
    }

    // ── Читання ──────────────────────────────────────────────────────────

    public static ListView list(UUID target) { return LISTS.get(key(target)); }

    public static Detail detail(String id) { return DETAILS.get(id); }

    public static long version() { return version; }

    // ── Запити ───────────────────────────────────────────────────────────

    public static void requestList(UUID target, String query, int outcome, int offset, int limit) {
        if (Minecraft.getInstance().getConnection() == null) return;
        ModNetwork.toServer(new HistoryListRequestPacket(target, query, outcome, offset, limit));
    }

    /** Запитує матч, якщо його ще нема в кеші (відповідь «заборонено» теж кешується). */
    public static void requestDetail(String id, UUID context) {
        if (DETAILS.containsKey(id)) return;
        forceRequestDetail(id, context);
    }

    public static void forceRequestDetail(String id, UUID context) {
        if (Minecraft.getInstance().getConnection() == null) return;
        ModNetwork.toServer(new HistoryDetailRequestPacket(id, context));
    }

    public static void clear() {
        LISTS.clear();
        DETAILS.clear();
        version++;
    }
}
