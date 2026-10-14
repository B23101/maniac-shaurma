package com.log_to_kot.maniacmod.leaderboard;

import com.log_to_kot.maniacmod.progression.profile.PlayerProfile;

import java.util.Locale;
import java.util.function.ToLongFunction;

/**
 * Усі таблиці лідерів мода. Одне місце правди й для сервера (звідки беруться числа), і для клієнта
 * (назви, групи, формат значення) — тож екран не має власного списку категорій.
 *
 * <p><b>Порядок значень — частина мережевого протоколу</b> (пакети шлють {@link #ordinal()}).
 * Нову категорію додавай ЛИШЕ В КІНЕЦЬ і підніми {@code PROTOCOL_VERSION} у {@code ModNetwork}.
 * Ключі локалізації: {@code maniacmod.lb.cat.<id>} і {@code maniacmod.lb.cat.<id>.desc}.</p>
 *
 * <p>Значення — завжди {@code long}: більше = краще. Гравець із значенням {@code <= 0} (або без
 * достатньої кількості матчів для відсотків) у таблицю не потрапляє.</p>
 */
public enum LeaderboardCategory {

    LEVEL(Group.OVERALL, Format.LEVEL, 0, PlayerProfile::totalXp),
    WINS(Group.OVERALL, Format.NUMBER, 0, p -> p.totalWins()),
    MATCHES(Group.OVERALL, Format.NUMBER, 0, p -> p.totalMatches()),
    /** Відсоток перемог у сотих частках відсотка (6250 = 62.5 %); лише від {@link #MIN_MATCHES_FOR_RATE} матчів. */
    WINRATE(Group.OVERALL, Format.PERCENT, 10,
        p -> p.totalMatches() <= 0 ? 0L : Math.round(p.totalWins() * 10000.0 / p.totalMatches())),

    ESCAPES(Group.SURVIVOR, Format.NUMBER, 0, p -> p.survivor.escapes),
    RESCUES(Group.SURVIVOR, Format.NUMBER, 0, p -> p.survivor.rescues),
    REPAIR_TIME(Group.SURVIVOR, Format.TIME, 0, p -> p.survivor.repairSeconds),
    BEST_SURVIVAL(Group.SURVIVOR, Format.TIME, 0, p -> p.survivor.bestSurvivedSeconds),
    SURVIVOR_STREAK(Group.SURVIVOR, Format.NUMBER, 0, p -> p.survivor.bestWinStreak),

    DOWNS(Group.MANIAC, Format.NUMBER, 0, p -> sum(p, m -> m.downs)),
    HOOKS(Group.MANIAC, Format.NUMBER, 0, p -> sum(p, m -> m.hooks)),
    HITS(Group.MANIAC, Format.NUMBER, 0, p -> sum(p, m -> m.hitsDealt)),
    TRAPS_CAUGHT(Group.MANIAC, Format.NUMBER, 0, p -> sum(p, PlayerProfile.Maniac::trapsCaught)),
    BEST_DOWNS(Group.MANIAC, Format.NUMBER, 0, p -> max(p, m -> m.bestDowns)),
    MANIAC_STREAK(Group.MANIAC, Format.NUMBER, 0, p -> max(p, m -> m.bestWinStreak));

    /** Вкладки екрана. Порядок — порядок вкладок. */
    public enum Group { OVERALL, SURVIVOR, MANIAC }

    /** Як показувати значення. */
    public enum Format { NUMBER, TIME, PERCENT, LEVEL }

    public static final int MIN_MATCHES_FOR_RATE = 10;

    private static final LeaderboardCategory[] ALL = values();

    private final Group group;
    private final Format format;
    private final int minMatches;
    private final ToLongFunction<PlayerProfile> extractor;

    LeaderboardCategory(Group group, Format format, int minMatches, ToLongFunction<PlayerProfile> extractor) {
        this.group = group;
        this.format = format;
        this.minMatches = minMatches;
        this.extractor = extractor;
    }

    public Group group() { return group; }

    public Format format() { return format; }

    /** Стабільний id для lang-ключів: {@code maniacmod.lb.cat.<id>}. */
    public String id() { return name().toLowerCase(Locale.ROOT); }

    public String nameKey() { return "maniacmod.lb.cat." + id(); }

    public String descKey() { return "maniacmod.lb.cat." + id() + ".desc"; }

    /** Значення профілю в цій категорії; {@code 0} — гравець у таблицю не потрапляє. */
    public long valueOf(PlayerProfile p) {
        if (p == null) return 0L;
        if (minMatches > 0 && p.totalMatches() < minMatches) return 0L;
        return Math.max(0L, extractor.applyAsLong(p));
    }

    public static int count() { return ALL.length; }

    /** Категорія за ordinal з мережі; {@code null}, якщо номер поза межами (клієнт новіший за сервер). */
    public static LeaderboardCategory byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < ALL.length ? ALL[ordinal] : null;
    }

    /** Усі категорії однієї вкладки за порядком оголошення. */
    public static java.util.List<LeaderboardCategory> of(Group group) {
        java.util.List<LeaderboardCategory> out = new java.util.ArrayList<>();
        for (LeaderboardCategory c : ALL) if (c.group == group) out.add(c);
        return out;
    }

    // ── Агрегати по маніяках ─────────────────────────────────────────────

    private static long sum(PlayerProfile p, ToLongFunction<PlayerProfile.Maniac> f) {
        long n = 0L;
        for (PlayerProfile.Maniac m : p.maniacs.values()) if (m != null) n += f.applyAsLong(m);
        return n;
    }

    private static long max(PlayerProfile p, ToLongFunction<PlayerProfile.Maniac> f) {
        long n = 0L;
        for (PlayerProfile.Maniac m : p.maniacs.values()) if (m != null) n = Math.max(n, f.applyAsLong(m));
        return n;
    }
}
