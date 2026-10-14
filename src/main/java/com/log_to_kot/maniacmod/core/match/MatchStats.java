package com.log_to_kot.maniacmod.core.match;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Особиста статистика матчу: лічильники по гравцях + мітки часу.
 *
 * <p>Живе всередині {@link MatchContext}, тож створюється заново разом із новим матчем і не
 * потребує окремого скидання. Package-private за тим самим правилом, що й контекст: ззовні —
 * лише через методи {@code MatchOrchestrator}. Час — у тіках матчу; «скільки грали» рахується
 * від першого входу у HUNT.</p>
 */
final class MatchStats {

    private static final long UNSET = -1L;

    private final Map<UUID, EnumMap<MatchStat, Long>> counters = new HashMap<>();
    /** Тік, на якому гравець вибув (втік/загинув). Решта виживих «дожили» до кінця матчу. */
    private final Map<UUID, Long> endTicks = new HashMap<>();
    /** Пастки по гравцях: id пастки → {поставлено, жертв}. LinkedHashMap — стабільний порядок у профілі. */
    private final Map<UUID, Map<String, int[]>> traps = new HashMap<>();
    private long playStartTick = UNSET;
    private long playEndTick = UNSET;

    void add(UUID id, MatchStat stat, long amount) {
        if (id == null || amount <= 0) return;
        counters.computeIfAbsent(id, k -> new EnumMap<>(MatchStat.class)).merge(stat, amount, Long::sum);
    }

    void addTrap(UUID id, String trapId, int placed, int caught) {
        if (id == null || trapId == null || (placed <= 0 && caught <= 0)) return;
        int[] v = traps.computeIfAbsent(id, k -> new java.util.LinkedHashMap<>())
            .computeIfAbsent(trapId, k -> new int[2]);
        v[0] += Math.max(0, placed);
        v[1] += Math.max(0, caught);
    }

    /** Знімок пасток гравця: id → {поставлено, жертв}. */
    Map<String, int[]> trapsOf(UUID id) {
        Map<String, int[]> m = traps.get(id);
        return m == null ? Map.of() : m;
    }

    long get(UUID id, MatchStat stat) {
        EnumMap<MatchStat, Long> map = counters.get(id);
        return map == null ? 0L : map.getOrDefault(stat, 0L);
    }

    /** Сума лічильника по всіх гравцях. */
    long total(MatchStat stat) {
        long sum = 0;
        for (EnumMap<MatchStat, Long> map : counters.values()) sum += map.getOrDefault(stat, 0L);
        return sum;
    }

    void stampEnd(UUID id, long tick) {
        endTicks.putIfAbsent(id, tick);
    }

    void markPlayStart(long tick) {
        if (playStartTick == UNSET) playStartTick = tick;
    }

    void markPlayEnd(long tick) {
        if (playEndTick == UNSET) playEndTick = tick;
    }

    boolean hasPlayStarted() { return playStartTick != UNSET; }

    /** Тік першого HUNT; {@code -1}, якщо до полювання матч не дійшов. */
    long playStartTick() { return playStartTick; }

    /** Скільки тіків тривала гра (до кінця матчу, якщо він завершений; інакше до {@code now}). */
    long playedTicks(long now) {
        if (playStartTick == UNSET) return 0L;
        long end = playEndTick != UNSET ? playEndTick : now;
        return Math.max(0L, end - playStartTick);
    }

    /** Скільки тіків гравець протримався: до вибуття або до кінця матчу. */
    long survivedTicks(UUID id, long now) {
        if (playStartTick == UNSET) return 0L;
        Long end = endTicks.get(id);
        long until = end != null ? end : (playEndTick != UNSET ? playEndTick : now);
        return Math.max(0L, until - playStartTick);
    }
}
