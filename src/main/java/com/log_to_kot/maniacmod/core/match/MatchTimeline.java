package com.log_to_kot.maniacmod.core.match;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.history.HistoryEventType;
import com.log_to_kot.maniacmod.history.MatchRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Хронологія ОДНОГО матчу: склад, відрізки фаз і події (удар, падіння, гак, втеча, генератор…).
 * Це сира «чорна скринька» — з неї {@link MatchRecordBuilder} збирає запис історії наприкінці матчу.
 *
 * <p>Живе всередині {@link MatchContext}, тож створюється заново разом із новим матчем і не потребує
 * окремого скидання. Package-private за тим самим правилом, що й контекст: ззовні — лише через методи
 * {@code MatchOrchestrator} ({@code recordXxx}). Час у тіках матчу — ті самі, що в {@link MatchStats}.</p>
 *
 * <p><b>Дебаг.</b> Матч, у якому хоч раз був увімкнений дебаг-режим, помічається
 * {@link #debugTainted()} і в історію НЕ потрапляє: одиночні перевірки не мають засмічувати її.</p>
 */
final class MatchTimeline {

    private final long startedAtMs = System.currentTimeMillis();

    /** Склад у порядку «маніяк, потім виживі»: індекс тут = індекс у {@code MatchRecord.players}. */
    private final List<UUID> roster = new ArrayList<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();

    private final List<MatchRecord.Event> events = new ArrayList<>();
    private final List<MatchRecord.PhaseSpan> spans = new ArrayList<>();

    /** Джерело останньої шкоди по гравцю: {@code maniac} або {@code trap:<id>}. */
    private final Map<UUID, String> lastDamageSource = new HashMap<>();
    /** Хто бив гравця останнім (маніяк); лише для ударів маніяка. */
    private final Map<UUID, UUID> lastHitter = new HashMap<>();

    private final Map<UUID, Integer> endTick = new HashMap<>();
    private final Map<UUID, String> endPhase = new HashMap<>();
    private final Map<UUID, String> endCause = new HashMap<>();

    private String currentPhase = "";
    private int currentPhaseStart;
    private boolean debugTainted;
    private boolean closed;

    // ── Склад ────────────────────────────────────────────────────────────

    /** Додає учасника в склад (маніяка — першим). Повторний виклик ім'я лише оновлює. */
    void register(UUID id, String name) {
        if (id == null) return;
        if (!names.containsKey(id)) roster.add(id);
        names.put(id, name == null || name.isEmpty() ? "?" : name);
        noteDebug();
    }

    int indexOf(UUID id) {
        return id == null ? -1 : roster.indexOf(id);
    }

    List<UUID> roster() { return List.copyOf(roster); }

    String nameOf(UUID id) { return names.get(id); }

    long startedAtMs() { return startedAtMs; }

    // ── Дебаг / закриття ─────────────────────────────────────────────────

    /** Фіксує, що в цьому матчі був дебаг. Викликається на кожній події — прапор залипає. */
    void noteDebug() {
        if (DebugMode.enabled()) debugTainted = true;
    }

    boolean debugTainted() { return debugTainted; }

    /** Після архівації матч «закритий»: нові події (RESET, LOBBY) в нього вже не пишуться. */
    void close() { closed = true; }

    boolean isClosed() { return closed; }

    // ── Фази ─────────────────────────────────────────────────────────────

    void onPhase(long tick, GamePhase phase) {
        if (closed) return;
        int t = clampTick(tick);
        if (!currentPhase.isEmpty()) spans.add(new MatchRecord.PhaseSpan(currentPhase, currentPhaseStart, t));
        currentPhase = phase.name();
        currentPhaseStart = t;
        add(tick, HistoryEventType.PHASE, null, null, currentPhase, 0);
    }

    String currentPhase() { return currentPhase; }

    /** Відрізки фаз; останній, ще відкритий, закривається на {@code nowTick}. */
    List<MatchRecord.PhaseSpan> spans(long nowTick) {
        List<MatchRecord.PhaseSpan> out = new ArrayList<>(spans);
        if (!currentPhase.isEmpty()) {
            out.add(new MatchRecord.PhaseSpan(currentPhase, currentPhaseStart,
                Math.max(currentPhaseStart, clampTick(nowTick))));
        }
        return out;
    }

    // ── Події ────────────────────────────────────────────────────────────

    void add(long tick, HistoryEventType type, UUID actor, UUID target, String arg, int value) {
        if (closed) return;
        noteDebug();
        if (!type.critical() && events.size() >= ManiacConfigs.get(ConfigSchema.HISTORY_MAX_EVENTS)) return;
        // Критичні події теж мають стелю — захист пакета від розпухання.
        if (events.size() >= MatchRecord.MAX_EVENTS) return;
        events.add(new MatchRecord.Event(clampTick(tick), type, indexOf(actor), indexOf(target), arg, value));
    }

    List<MatchRecord.Event> events() { return List.copyOf(events); }

    /** Маніяк влучив: запам'ятовуємо джерело шкоди для пізнішої причини вибуття. */
    void noteHit(UUID victim, UUID attacker) {
        if (victim == null) return;
        lastDamageSource.put(victim, "maniac");
        if (attacker != null) lastHitter.put(victim, attacker);
    }

    /** Пастка зачепила жертву. */
    void noteTrap(UUID victim, String trapId) {
        if (victim == null) return;
        lastDamageSource.put(victim, "trap:" + trapId);
        lastHitter.remove(victim);
    }

    UUID lastHitterOf(UUID victim) { return lastHitter.get(victim); }

    /** Причина вибуття: останнє джерело шкоди; якщо його не було — кровотеча/падіння. */
    String causeOf(UUID victim) {
        return lastDamageSource.getOrDefault(victim, "bleedout");
    }

    // ── Кінець гравця ────────────────────────────────────────────────────

    /** Гравець вибув/втік/вийшов: тік, фаза й причина фіксуються раз (перший запис виграє). */
    void markOut(UUID id, long tick, String cause) {
        if (id == null || endTick.containsKey(id)) return;
        endTick.put(id, clampTick(tick));
        endPhase.put(id, currentPhase);
        endCause.put(id, cause == null ? "" : cause);
    }

    int endTickOf(UUID id) { return endTick.getOrDefault(id, -1); }
    String endPhaseOf(UUID id) { return endPhase.getOrDefault(id, ""); }
    String endCauseOf(UUID id) { return endCause.getOrDefault(id, ""); }

    private static int clampTick(long tick) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, tick));
    }
}
