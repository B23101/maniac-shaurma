package com.log_to_kot.maniacmod.client.style;

import com.log_to_kot.maniacmod.progression.Ranks;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Емблема рангу — ромб із ядром, що росте зі ступенем, і п'ять позначок дивізіону під ним.
 * Лише срібна палітра {@link GeneratorFuelUiTheme}: ступінь видно з яскравості й розміру ядра,
 * а не з кольору (колір на екрані статистики лишається за результатом матчу).
 *
 * <p>Без анімації всередині: плавність дає той, хто викликає (масштаб {@code radius} і альфа).</p>
 */
public final class RankFx {

    private RankFx() {}

    /** Колір ступеня: від приглушеного до світлого срібла. */
    public static int tierColor(int tier) {
        return GeneratorFuelUiTheme.mix(GeneratorFuelUiTheme.GHOST, GeneratorFuelUiTheme.HIGHLIGHT,
            Math.max(0, tier) / (float) (Ranks.TIERS - 1));
    }

    /** Колір ступеня з урахуванням ролі: виживий — сріблястий, маніяк — від темного до яскравого багряного. */
    public static int tierColor(Ranks.Role role, int tier) {
        if (role != Ranks.Role.MANIAC) return tierColor(tier);
        return GeneratorFuelUiTheme.mix(0xFF6A1F27, 0xFFFF5A5A, Math.max(0, tier) / (float) (Ranks.TIERS - 1));
    }

    /** Висота, яку займає емблема разом із позначками дивізіону (для розкладки). */
    public static int height(int radius) {
        return radius * 2 + 1 + 8;
    }

    /**
     * @param cx,cy  центр ромба
     * @param radius піврозмір ромба по діагоналі (px)
     * @param alpha  загальна прозорість 0..1
     */
    public static void draw(GuiGraphics g, int cx, int cy, int radius, Ranks.Rank rank, float alpha) {
        if (radius < 4 || alpha <= 0.01f) return;
        int col = tierColor(rank.role(), rank.tier());

        diamond(g, cx, cy, radius, GeneratorFuelUiTheme.alpha(col, alpha));
        diamond(g, cx, cy, radius - 2, GeneratorFuelUiTheme.alpha(ManiacUiTheme.MENU_PANEL_DARK, alpha));
        int core = 2 + Math.round((radius - 6) * (rank.tier() + 1) / (float) Ranks.TIERS);
        diamond(g, cx, cy, Math.max(2, core), GeneratorFuelUiTheme.alpha(col, alpha));

        // Позначки дивізіону: 5 квадратиків, заповнені до поточного дивізіону.
        int pip = 3, gap = 2;
        int total = Ranks.DIVISIONS * pip + (Ranks.DIVISIONS - 1) * gap;
        int px = cx - total / 2;
        int py = cy + radius + 5;
        int lit = rank.top() ? Ranks.DIVISIONS : rank.division();
        for (int i = 0; i < Ranks.DIVISIONS; i++) {
            int x = px + i * (pip + gap);
            int c = i < lit ? col : ManiacUiTheme.BORDER;
            g.fill(x, py, x + pip, py + pip, GeneratorFuelUiTheme.alpha(c, alpha));
        }
    }

    private static void diamond(GuiGraphics g, int cx, int cy, int r, int argb) {
        if (r <= 0 || (argb >>> 24) == 0) return;
        for (int dy = -r; dy <= r; dy++) {
            int half = r - Math.abs(dy);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, argb);
        }
    }
}
