package com.log_to_kot.maniacmod.client.overlay.roster;

import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Піксельні іконки tab-екрана. Кожна — маленька бітова мапа (# = піксель),
 * тож жодних текстур: малюється звичайним {@code fill}, як і решта панелі,
 * і не залежить від ресурспаків.
 *
 * ── Де вживається ────────────────────────────────────────────────────
 *   • стан гравця (рядок виживого, картка «ТИ», вибулі);
 *   • віхи матчу у верхній картці;
 *   • рядки «ЗВЕДЕННЯ».
 *
 * Усі іконки висотою ≤ 7 px — вміщаються в рядок із текстом масштабу 0.7.
 */
final class TabIcons {

    private TabIcons() {}

    enum Icon {
        HEART(
            ".##.##.",
            "#######",
            "#######",
            ".#####.",
            "..###..",
            "...#..."),
        BONE(
            "....##.",
            "...####",
            "...###.",
            "..###..",
            ".###...",
            "####...",
            ".##...."),
        CRAWL(
            "#.....#",
            "##...##",
            ".##.##.",
            "..###..",
            "...#..."),
        DOWNED(
            "...#...",
            "..###..",
            "..#.#..",
            ".##.##.",
            ".#####.",
            "###.###",
            "#######"),
        ESCAPED(
            "...#...",
            "....#..",
            "#######",
            "....#..",
            "...#..."),
        SKULL(
            ".#####.",
            "#######",
            "#.###.#",
            "#.###.#",
            ".#####.",
            "..#.#..",
            "..###.."),
        BOLT(
            "...##.",
            "..##..",
            ".###..",
            ".#####",
            "..###.",
            "..##..",
            ".#...."),
        DOOR(
            ".#####.",
            ".#...#.",
            ".#...#.",
            ".#..##.",
            ".#...#.",
            ".#...#.",
            "#######"),
        DROP(
            "...#...",
            "..###..",
            "..###..",
            ".#####.",
            ".#####.",
            ".#####.",
            "..###..");

        final String[] rows;
        final int w;
        final int h;

        Icon(String... rows) {
            this.rows = rows;
            this.h = rows.length;
            int mw = 0;
            for (String r : rows) mw = Math.max(mw, r.length());
            this.w = mw;
        }
    }

    /** Іконка стану виживого. {@code null} — для стану іконка не потрібна. */
    static Icon forState(SurvivorState s) {
        return switch (s) {
            case HEALTHY     -> Icon.HEART;
            case BROKEN_LEG  -> Icon.BONE;
            case CRAWLING    -> Icon.CRAWL;
            case UNCONSCIOUS -> Icon.DOWNED;
            case ESCAPED     -> Icon.ESCAPED;
            case ELIMINATED  -> Icon.SKULL;
        };
    }

    /** Іконка рядка «ЗВЕДЕННЯ» за ключем статистики. */
    static Icon forStat(String key) {
        return switch (key) {
            case "alive"   -> Icon.HEART;
            case "downed"  -> Icon.DOWNED;
            case "escaped" -> Icon.ESCAPED;
            default        -> Icon.SKULL; // out / kills
        };
    }

    /** Ширина іконки в пікселях при цілому масштабі {@code scale}. */
    static int width(Icon icon, int scale) {
        return icon.w * scale;
    }

    static int height(Icon icon, int scale) {
        return icon.h * scale;
    }

    /**
     * Малює іконку з лівого верхнього кута. Піксель-у-піксель: щоб текстура
     * не «пливла» при дробових координатах панелі, x/y — цілі.
     * Тонка темна «підкладка» (зсув +1,+1) тримає читабельність на яскравому фоні.
     */
    static void draw(GuiGraphics g, Icon icon, int x, int y, int argb, int scale) {
        if ((argb >>> 24) < 6) return;
        int shadow = (Math.round((argb >>> 24) * 0.55f) << 24);
        for (int pass = 0; pass < 2; pass++) {
            int ox = pass == 0 ? scale : 0;
            int oy = pass == 0 ? scale : 0;
            int color = pass == 0 ? shadow : argb;
            if ((color >>> 24) < 6) continue;
            for (int r = 0; r < icon.h; r++) {
                String row = icon.rows[r];
                int c = 0;
                while (c < row.length()) {
                    if (row.charAt(c) != '#') { c++; continue; }
                    int start = c;
                    while (c < row.length() && row.charAt(c) == '#') c++;
                    g.fill(x + ox + start * scale, y + oy + r * scale,
                           x + ox + c * scale,     y + oy + (r + 1) * scale, color);
                }
            }
        }
    }
}
