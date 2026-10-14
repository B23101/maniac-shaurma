package com.log_to_kot.maniacmod.progression;

/**
 * Крива рівнів. Єдине місце правди для сервера (нарахування) й клієнта (смужка прогресу).
 *
 * <p>Рівень {@code L} (від 1) закривається, коли в ньому набрано {@code 50 + 250 * L} XP:
 * рівень 1 — 300, рівень 2 — 550, рівень 3 — 800… Загальний XP зберігається одним числом,
 * рівень і прогрес у ньому — похідні.</p>
 */
public final class XpCurve {

    private XpCurve() {}

    /** Скільки XP треба набрати всередині рівня {@code level}, щоб перейти на наступний. */
    public static int needed(int level) {
        return 50 + 250 * Math.max(1, level);
    }

    /** Рівень за загальним XP. */
    public static int levelOf(long totalXp) {
        int level = 1;
        long left = Math.max(0L, totalXp);
        while (left >= needed(level)) {
            left -= needed(level);
            level++;
        }
        return level;
    }

    /** XP, уже набраний усередині поточного рівня. */
    public static int intoLevel(long totalXp) {
        int level = 1;
        long left = Math.max(0L, totalXp);
        while (left >= needed(level)) {
            left -= needed(level);
            level++;
        }
        return (int) left;
    }
}
