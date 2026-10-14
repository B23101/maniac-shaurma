package com.log_to_kot.maniacmod.history;

/**
 * Тип запису в хронології матчу ({@link MatchRecord.Event}).
 *
 * <p>Пишеться мережею по {@code ordinal()} і на диск — по імені. Нові значення — ЛИШЕ в кінець
 * (інакше старі пакети/файли прочитаються як чужі події).</p>
 *
 * <p>Значення полів події залежать від типу — це єдине місце, де вони описані:</p>
 * <pre>
 *  MATCH_START     arg = id маніяка
 *  PHASE           arg = ім'я {@code GamePhase}
 *  HIT             actor = маніяк, target = жертва, value = хп жертви ДО удару
 *  DOWNED          target = хто впав, actor = хто бив останнім (або -1)
 *  HOOKED          target = жертва, actor = маніяк
 *  RESCUED         actor = рятівник, target = врятований
 *  ESCAPED         actor = хто втік
 *  ELIMINATED      target = хто вибув, actor = останній, хто бив (або -1),
 *                  arg = причина: "maniac" | "trap:&lt;id&gt;" | "bleedout"
 *  GENERATOR_DONE  actor = хто добив (або -1), value = скільки вже готово, arg = "x,y,z"
 *  OBJECTIVE       arg = ім'я {@code MatchObjectives.Objective}
 *  TRAP_PLACED     actor = маніяк, arg = id пастки
 *  TRAP_TRIGGERED  target = жертва (або -1), arg = id пастки
 *  PLAYER_LEFT     actor = хто вийшов, arg = "body" (лишив тіло) | "gone"
 *  FORCE_END       arg = "survivors" | "maniac" (адмін завершив матч)
 * </pre>
 */
public enum HistoryEventType {
    MATCH_START,
    PHASE,
    HIT,
    DOWNED,
    HOOKED,
    RESCUED,
    ESCAPED,
    ELIMINATED,
    GENERATOR_DONE,
    OBJECTIVE,
    TRAP_PLACED,
    TRAP_TRIGGERED,
    PLAYER_LEFT,
    FORCE_END;

    private static final HistoryEventType[] VALUES = values();

    /** Безпечне читання з мережі: сміття не кидає виняток, а стає {@link #PHASE}-нейтральним значенням. */
    public static HistoryEventType byOrdinal(int ordinal) {
        return VALUES[Math.floorMod(ordinal, VALUES.length)];
    }

    /** Події, які не можна відкинути навіть при переповненні журналу. */
    public boolean critical() {
        return switch (this) {
            case MATCH_START, PHASE, ESCAPED, ELIMINATED, GENERATOR_DONE, OBJECTIVE, FORCE_END, PLAYER_LEFT -> true;
            default -> false;
        };
    }
}
