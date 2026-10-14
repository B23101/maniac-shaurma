package com.log_to_kot.maniacmod.client.screen.leaderboard;

import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacTabTheme;
import com.log_to_kot.maniacmod.leaderboard.LeaderboardCategory;
import com.log_to_kot.maniacmod.progression.Ranks;
import com.log_to_kot.maniacmod.progression.XpCurve;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Locale;

/**
 * Безстанові дрібниці екрана лідербордів: текст із масштабом, кольори місць і вкладок, формати значень.
 * Власного вигляду нема — кольори беруться з {@code ManiacTabTheme}/{@code GeneratorFuelUiTheme}, як в екранів історії.
 */
@OnlyIn(Dist.CLIENT)
final class LeaderboardUi {

    static final int GOLD = 0xFFF0C24B;
    static final int SILVER_MEDAL = 0xFFD5DCE8;
    static final int BRONZE = 0xFFCD7F42;

    private LeaderboardUi() {}

    // ── Текст ────────────────────────────────────────────────────────────

    static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /** Текст зі scale й вирівнюванням: 0 — ліворуч, 1 — по центру, 2 — праворуч. */
    static void text(GuiGraphics g, Font font, String s, float x, float y, float scale, int argb, int align) {
        if (s == null || s.isEmpty() || ((argb >>> 24) & 0xFF) < 8) return;
        int w = font.width(s);
        float ox = align == 1 ? -w / 2f : align == 2 ? -w : 0f;
        g.pose().pushPose();
        g.pose().translate(x, y, 0f);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, s, Math.round(ox), 0, argb, false);
        g.pose().popPose();
    }

    /** Обрізає рядок трикрапкою, щоб він влазив у {@code maxPx} при заданому scale. */
    static String fit(Font font, String s, float scale, float maxPx) {
        if (s == null) return "";
        if (font.width(s) * scale <= maxPx) return s;
        int end = s.length();
        while (end > 1 && font.width(s.substring(0, end) + "…") * scale > maxPx) end--;
        return s.substring(0, Math.max(0, end)) + "…";
    }

    static int a(int argb, float mul) { return GeneratorFuelUiTheme.alpha(argb, mul); }

    static int mix(int x, int y, float t) { return GeneratorFuelUiTheme.mix(x, y, t); }

    static boolean inside(double mx, double my, int[] r) {
        return r[2] > 0 && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    // ── Кольори ──────────────────────────────────────────────────────────

    /** Золото / срібло / бронза для місць 1–3; 0 — звичайне місце. */
    static int medal(int rank) {
        return switch (rank) {
            case 1 -> GOLD;
            case 2 -> SILVER_MEDAL;
            case 3 -> BRONZE;
            default -> 0;
        };
    }

    /** Колір місця для тексту: медаль у трійці, інакше срібло. */
    static int rankColor(int rank) {
        int m = medal(rank);
        return m != 0 ? m : ManiacTabTheme.DIM;
    }

    /** Колір вкладки: загальне — срібло, виживший — блакить, маніяк — багрянець. */
    static int accent(LeaderboardCategory.Group g) {
        return switch (g) {
            case OVERALL -> ManiacTabTheme.SILVER;
            case SURVIVOR -> ManiacTabTheme.CYAN;
            case MANIAC -> ManiacTabTheme.BLOOD_HOT;
        };
    }

    // ── Формати значень ──────────────────────────────────────────────────

    /** {@code 12 450} — розряди пробілом, щоб довгі числа читались. */
    static String number(long v) {
        return String.format(Locale.ROOT, "%,d", v).replace(',', ' ');
    }

    /** {@code 3г 05хв} або {@code 12хв 07с}. */
    static String duration(long seconds) {
        seconds = Math.max(0L, seconds);
        if (seconds >= 3600L) {
            return String.format(Locale.ROOT, "%d%s %02d%s", seconds / 3600L, tr("maniacmod.profile.unit.h"),
                (seconds % 3600L) / 60L, tr("maniacmod.profile.unit.m"));
        }
        return String.format(Locale.ROOT, "%d%s %02d%s", seconds / 60L, tr("maniacmod.profile.unit.m"),
            seconds % 60L, tr("maniacmod.profile.unit.s"));
    }

    /** Головне значення рядка — те, за чим ранжують. */
    static String main(LeaderboardCategory c, long v) {
        return switch (c.format()) {
            case NUMBER -> number(v);
            case TIME -> duration(v);
            case PERCENT -> String.format(Locale.ROOT, "%.1f%%", v / 100.0);
            case LEVEL -> tr("maniacmod.lb.row.level", XpCurve.levelOf(v));
        };
    }

    /** Другорядне значення під головним; {@code null}, якщо нема. */
    static String sub(LeaderboardCategory c, long v) {
        return c.format() == LeaderboardCategory.Format.LEVEL ? number(v) + " XP" : null;
    }

    /** Різниця між двома значеннями в тому ж форматі («бракує 12 хв 05 с»). */
    static String delta(LeaderboardCategory c, long diff) {
        diff = Math.max(0L, diff);
        return switch (c.format()) {
            case NUMBER -> number(diff);
            case TIME -> duration(diff);
            case PERCENT -> String.format(Locale.ROOT, "%.1f%%", diff / 100.0);
            case LEVEL -> number(diff) + " XP";
        };
    }

    /** Назва рангу за рівнем: «Ветеран III». Ролі вкладки визначають драбину назв. */
    static String rankName(LeaderboardCategory.Group group, int level) {
        Ranks.Role role = group == LeaderboardCategory.Group.MANIAC ? Ranks.Role.MANIAC : Ranks.Role.SURVIVOR;
        Ranks.Rank r = Ranks.ofLevel(role, level);
        String name = tr(r.langKey());
        return r.top() ? name : name + " " + r.divisionRoman();
    }

    /** «5 хв тому» тощо — ті самі ключі, що в історії матчів. */
    static String ago(long ms) {
        long sec = Math.max(0L, (System.currentTimeMillis() - ms) / 1000L);
        if (sec < 60L) return tr("maniacmod.history.ago.now");
        if (sec < 3600L) return tr("maniacmod.history.ago.min", sec / 60L);
        if (sec < 86400L) return tr("maniacmod.history.ago.hour", sec / 3600L);
        return tr("maniacmod.history.ago.day", sec / 86400L);
    }

    static boolean exists(String key) { return I18n.exists(key); }

    // ── Піксельна корона ─────────────────────────────────────────────────

    /** Корона для чемпіона; {@code s} — розмір «пікселя». Центр по X — {@code cx}, верх — {@code y}. */
    static void crown(GuiGraphics g, int cx, int y, int s, int argb) {
        int dark = a(0xFF1A1205, (argb >>> 24) / 255f);
        g.fill(cx - 5 * s, y + 2 * s, cx + 5 * s, y + 5 * s, argb);          // корпус
        g.fill(cx - 5 * s, y, cx - 3 * s, y + 2 * s, argb);                   // ліва вежа
        g.fill(cx - s, y - s, cx + s, y + 2 * s, argb);                       // центральна (вища)
        g.fill(cx + 3 * s, y, cx + 5 * s, y + 2 * s, argb);                   // права вежа
        g.fill(cx - 5 * s, y + 4 * s, cx + 5 * s, y + 5 * s, dark);           // тінь основи
        g.fill(cx - 3 * s, y + 3 * s, cx - 2 * s, y + 4 * s, dark);           // «камені»
        g.fill(cx, y + 3 * s, cx + s, y + 4 * s, dark);
        g.fill(cx + 3 * s, y + 3 * s, cx + 4 * s, y + 4 * s, dark);
    }
}
