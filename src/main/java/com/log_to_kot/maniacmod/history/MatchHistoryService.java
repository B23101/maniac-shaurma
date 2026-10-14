package com.log_to_kot.maniacmod.history;

import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Історія матчів: архівація, списки, відкриття одного матчу. Один екземпляр на сервер — його тримає
 * {@code MatchOrchestrator} (поруч з іншими модулями), тож у моді немає ні нової статики, ні другого
 * власника цих даних.
 *
 * <p><b>Потоки.</b> Усе, що читає/міняє стан, викликається з головного потоку сервера. Лише запис на
 * диск іде у власний потік — посеред матчу диск не має смикати сервер. Запис ставиться в чергу ПІСЛЯ
 * оновлення індексу й кешу в пам'яті, тож щойно завершений матч видно одразу, не чекаючи диска.</p>
 *
 * <p><b>Пам'ять.</b> Індекс — короткі рядки ({@link MatchBrief}), повні записи — лише в невеликому
 * LRU-кеші; решта читається з диска при відкритті матчу.</p>
 */
public final class MatchHistoryService {

    private static final Logger LOGGER = LogManager.getLogger("MatchHistory");
    private static final int CACHE_SIZE = 24;
    public static final int MAX_PAGE = 50;

    /** Одна сторінка списку: скільки всього матчів під фільтром і сам зріз (новіші першими). */
    public record Page(int total, int offset, List<MatchBrief> items) {}

    /** Фільтр за результатом. Нові значення — лише в кінець (їде мережею по ordinal). */
    public enum OutcomeFilter { ALL, SURVIVORS_WON, MANIAC_WON }

    private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "maniac-history-io");
        t.setDaemon(true);
        return t;
    });

    /** За зростанням часу; {@code null}, доки не завантажено. */
    private List<MatchBrief> index;
    private Path loadedRoot;

    private final Map<String, MatchRecord> cache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MatchRecord> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    // ── Архівація ────────────────────────────────────────────────────────

    /** Зберігає завершений матч: пам'ять — одразу, диск — у фоні. */
    public synchronized void archive(MinecraftServer server, MatchRecord record) {
        Path root = MatchHistoryStorage.root(server);
        ensureIndex(root);
        MatchBrief brief = record.toBrief();
        index.add(brief);
        cache.put(record.id, record);
        io.execute(() -> {
            try {
                MatchHistoryStorage.writeRecord(root, record);
                MatchHistoryStorage.appendIndex(root, brief);
            } catch (Exception e) {
                LOGGER.error("Матч {} не збережено на диск: {}", record.id, e.toString());
            }
        });
        LOGGER.info("Матч {} записано в історію ({} гравців, {} подій)",
            record.id, record.players.size(), record.events.size());
    }

    // ── Читання ──────────────────────────────────────────────────────────

    /**
     * Сторінка списку, новіші першими.
     *
     * @param target  {@code null} — усі матчі (адмінський список); інакше лише ті, де брав участь цей гравець
     * @param query   нік гравця або id маніяка (підрядок, регістр не важливий); порожній — без фільтра
     */
    public synchronized Page list(MinecraftServer server, UUID target, String query,
                                  OutcomeFilter outcome, int offset, int limit) {
        ensureIndex(MatchHistoryStorage.root(server));
        String needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        int from = Math.max(0, offset);
        int size = Math.max(1, Math.min(MAX_PAGE, limit));

        List<MatchBrief> items = new ArrayList<>(size);
        int total = 0;
        for (int i = index.size() - 1; i >= 0; i--) {
            MatchBrief b = index.get(i);
            if (target != null && !b.involves(target)) continue;
            if (outcome == OutcomeFilter.SURVIVORS_WON && !b.survivorsWon) continue;
            if (outcome == OutcomeFilter.MANIAC_WON && b.survivorsWon) continue;
            if (!b.matchesText(needle)) continue;
            if (total >= from && items.size() < size) items.add(b);
            total++;
        }
        return new Page(total, from, items);
    }

    /** Повний матч; {@code null}, якщо його нема. Читає диск лише при промаху кешу. */
    public synchronized MatchRecord get(MinecraftServer server, String id) {
        if (!MatchHistoryStorage.validId(id)) return null;
        MatchRecord cached = cache.get(id);
        if (cached != null) return cached;
        MatchRecord loaded = MatchHistoryStorage.readRecord(MatchHistoryStorage.root(server), id);
        if (loaded != null) cache.put(id, loaded);
        return loaded;
    }

    /** Скільки матчів записано загалом. */
    public synchronized int count(MinecraftServer server) {
        ensureIndex(MatchHistoryStorage.root(server));
        return index.size();
    }

    // ── Життєвий цикл ────────────────────────────────────────────────────

    /** Зупинка сервера: дописуємо те, що ще в черзі запису. */
    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.warn("Запис історії не встиг завершитись за 10 с");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void ensureIndex(Path root) {
        if (index != null && root.equals(loadedRoot)) return;
        index = new ArrayList<>(MatchHistoryStorage.loadIndex(root));
        loadedRoot = root;
        cache.clear();
    }
}
