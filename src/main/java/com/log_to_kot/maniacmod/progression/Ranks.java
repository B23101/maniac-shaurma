package com.log_to_kot.maniacmod.progression;

/**
 * Система рангів. Ранг — похідна від рівня ({@link XpCurve}), нічого окремо не зберігається:
 * ні на сервері, ні в профілі. Коли з'явиться екран профілю, він бере ранг звідси ж.
 *
 * <p>Прогрес (XP, рівень) СПІЛЬНИЙ для обох ролей, а ранги — окремі: виживий і маніяк мають свої
 * назви й вигляд ступенів ({@link Role}), пороги рівнів однакові. Ключі назв: виживий —
 * {@code maniacmod.rank.<id>}, маніяк — {@code maniacmod.rank.maniac.<id>}.</p>
 *
 * <p>Сім ступенів (tier). У ступені рівні діляться на дивізіони I–V; найвищий ступінь
 * безмежний — там показується лише номер рівня. Назви — ключі
 * {@code maniacmod.rank.<id>}.</p>
 *
 * <pre>
 *  ступінь   id         рівні
 *  0         rookie      1–4
 *  1         witness     5–9
 *  2         runner     10–14
 *  3         survivor   15–19
 *  4         veteran    20–29
 *  5         shadow     30–39
 *  6         legend     40+
 * </pre>
 */
public final class Ranks {

    private Ranks() {}

    /** Роль, чию драбину рангів показуємо. Пороги рівнів у ролей однакові. */
    public enum Role { SURVIVOR, MANIAC }

    private static final String[] SURVIVOR_IDS = {"rookie", "witness", "runner", "survivor", "veteran", "shadow", "legend"};
    private static final String[] MANIAC_IDS = {"lurker", "stalker", "hunter", "butcher", "reaper", "nightmare", "executioner"};
    private static final int[] MIN_LEVEL = {1, 5, 10, 15, 20, 30, 40};

    public static final int TIERS = SURVIVOR_IDS.length;
    public static final int DIVISIONS = 5;

    /**
     * @param tier      індекс ступеня 0..{@link #TIERS}-1
     * @param division  дивізіон 1..{@link #DIVISIONS} (для останнього ступеня завжди 1)
     * @param top       true — найвищий ступінь (дивізіонів нема)
     */
    public record Rank(Role role, int tier, int division, boolean top) {

        /** Ранг виживого (сумісний конструктор). */
        public Rank(int tier, int division, boolean top) { this(Role.SURVIVOR, tier, division, top); }

        public String id() { return idOf(role, tier); }

        public String langKey() { return keyOf(role, tier); }

        /** «I»…«V»; порожньо для найвищого ступеня. */
        public String divisionRoman() {
            if (top) return "";
            return switch (division) {
                case 1 -> "I";
                case 2 -> "II";
                case 3 -> "III";
                case 4 -> "IV";
                default -> "V";
            };
        }
    }

    /** Найменший рівень ступеня {@code tier} (0..{@link #TIERS}-1). */
    public static int minLevel(int tier) {
        return MIN_LEVEL[Math.max(0, Math.min(TIERS - 1, tier))];
    }

    static String idOf(Role role, int tier) {
        String[] ids = role == Role.MANIAC ? MANIAC_IDS : SURVIVOR_IDS;
        return ids[Math.max(0, Math.min(TIERS - 1, tier))];
    }

    /** Ключ локалізації назви ступеня. */
    public static String keyOf(Role role, int tier) {
        return (role == Role.MANIAC ? "maniacmod.rank.maniac." : "maniacmod.rank.") + idOf(role, tier);
    }

    public static Rank ofLevel(int level) { return ofLevel(Role.SURVIVOR, level); }

    public static Rank ofLevel(Role role, int level) {
        int lvl = Math.max(1, level);
        int tier = 0;
        for (int i = 0; i < TIERS; i++) if (lvl >= MIN_LEVEL[i]) tier = i;
        if (tier == TIERS - 1) return new Rank(role, tier, 1, true);
        int width = MIN_LEVEL[tier + 1] - MIN_LEVEL[tier];
        int into = lvl - MIN_LEVEL[tier];
        int division = Math.min(DIVISIONS, 1 + into * DIVISIONS / width);
        return new Rank(role, tier, division, false);
    }

    public static Rank ofXp(long totalXp) { return ofXp(Role.SURVIVOR, totalXp); }

    public static Rank ofXp(Role role, long totalXp) {
        return ofLevel(role, XpCurve.levelOf(totalXp));
    }

    /** Рівень, з якого починається наступний ступінь; {@code -1}, якщо ступінь найвищий. */
    public static int nextTierLevel(int level) {
        Rank r = ofLevel(level);
        return r.top() ? -1 : MIN_LEVEL[r.tier() + 1];
    }

    /** Ключ назви наступного ступеня; {@code null}, якщо ступінь найвищий. */
    public static String nextTierKey(int level) { return nextTierKey(Role.SURVIVOR, level); }

    public static String nextTierKey(Role role, int level) {
        Rank r = ofLevel(role, level);
        return r.top() ? null : keyOf(role, r.tier() + 1);
    }
}
