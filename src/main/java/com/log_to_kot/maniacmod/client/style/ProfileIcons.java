package com.log_to_kot.maniacmod.client.style;

import net.minecraft.client.gui.GuiGraphics;

import java.util.Arrays;

/**
 * Піксельні іконки екрана профілю — 16×16 «клітинок», намальовані заливками (без текстур), кожна
 * зі своєю анімацією: шестірня крутиться, серце б'ється, гак гойдається, годинникова стрілка йде,
 * полум'я мерехтить, двері випускають стрілку, щит труситься, лезо ріже, кулак б'є, корона й
 * кубок блищать.
 *
 * <p>Кожна іконка спершу малюється в буфер 16×16 ({@link Cv}), і лише потім виливається на екран
 * горизонтальними смугами цілих клітинок. Жодних дробових поворотів/масштабів матрицею: поворот шестірні
 * рахується по клітинках, гойдання гака — зсувом рядків, «биття» — ореолом в 1 клітинку. Тому краї завжди
 * чіткі й нічого не «пливе».</p>
 *
 * <p>Фаза анімації накопичується окремо для кожної іконки ({@code phase}), а не береться як
 * {@code epoch · (1 + hover)} — інакше при наведенні фаза стрибала б на мільйони радіанів.</p>
 */
public final class ProfileIcons {

    private ProfileIcons() {}

    public enum Icon { PLAY, DOOR, GEAR, HEART, HOOK, SHIELD, CLOCK, FLAME, TROPHY, BLADE, FIST, CROWN }

    private static final int HOLE = 0xFF05070A;

    private static final int[][] RING;

    static {
        java.util.List<int[]> ring = new java.util.ArrayList<>();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double d = Math.hypot(x + 0.5 - 8, y + 0.5 - 8);
                if (d >= 5.6 && d <= 7.4) ring.add(new int[]{x, y});
            }
        }
        RING = ring.toArray(new int[0][]);
    }

    // ── Фаза анімації (по одній на іконку) ───────────────────────────────

    private static final double[] PHASE = new double[Icon.values().length];
    private static final long[] LAST_NS = new long[Icon.values().length];

    /** Накопичує час іконки; наведення лише пришвидшує біг, фаза не стрибає. */
    private static double advance(Icon icon, float hover) {
        int i = icon.ordinal();
        long now = System.nanoTime();
        // Кілька викликів в одному кадрі (дві однакові іконки) не мають подвоювати час.
        if (now - LAST_NS[i] > 2_000_000L) {
            double dt = LAST_NS[i] == 0L ? 0.0 : Math.min(0.1, (now - LAST_NS[i]) / 1e9);
            PHASE[i] += dt * (1.0 + 0.6 * hover);
            LAST_NS[i] = now;
        }
        return PHASE[i];
    }

    // ── Піксельний буфер 16×16 ───────────────────────────────────────────

    private static final class Cv {
        final int[] px = new int[256];

        void clear() { Arrays.fill(px, 0); }

        void r(int x, int y, int w, int h, int argb) {
            for (int yy = Math.max(0, y); yy < Math.min(16, y + h); yy++) {
                for (int xx = Math.max(0, x); xx < Math.min(16, x + w); xx++) px[yy * 16 + xx] = argb;
            }
        }

        void set(int x, int y, int argb) {
            if (x >= 0 && x < 16 && y >= 0 && y < 16) px[y * 16 + x] = argb;
        }

        int get(int x, int y) {
            return x >= 0 && x < 16 && y >= 0 && y < 16 ? px[y * 16 + x] : 0;
        }

        /**
         * Малює піксель-арт із рядків: '#' — основний колір, 'o' — темний, '+' — світлий,
         * '.' чи пробіл — порожньо. Рядки ліворуч від {@code x0}, зверху від {@code y0}.
         */
        void art(int x0, int y0, int c, int dim, int hi, String... rows) {
            for (int y = 0; y < rows.length; y++) {
                String row = rows[y];
                for (int x = 0; x < row.length(); x++) {
                    char ch = row.charAt(x);
                    int col = ch == '#' ? c : ch == 'o' ? dim : ch == '+' ? hi : 0;
                    if (col != 0) set(x0 + x, y0 + y, col);
                }
            }
        }

        /** Світлішає вже намальовані клітинки у прямокутнику (замість напівпрозорої накладки). */
        void tint(int x, int y, int w, int h, int with, float a) {
            for (int yy = Math.max(0, y); yy < Math.min(16, y + h); yy++) {
                for (int xx = Math.max(0, x); xx < Math.min(16, x + w); xx++) {
                    int v = px[yy * 16 + xx];
                    if ((v >>> 24) != 0) px[yy * 16 + xx] = GeneratorFuelUiTheme.mix(v, with, a);
                }
            }
        }

        /** Копія зі зсувом на ціле число клітинок. */
        void copyShifted(Cv src, int dx, int dy) {
            clear();
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int v = src.px[y * 16 + x];
                    if ((v >>> 24) != 0) set(x + dx, y + dy, v);
                }
            }
        }

        /** Гойдання: кожен рядок зсувається на {@code round(k·(y−pivotY))} — маятник без поворотів. */
        void copySheared(Cv src, int pivotY, double k) {
            clear();
            for (int y = 0; y < 16; y++) {
                int off = (int) Math.round(k * (y - pivotY));
                for (int x = 0; x < 16; x++) {
                    int v = src.px[y * 16 + x];
                    if ((v >>> 24) != 0) set(x + off, y, v);
                }
            }
        }

        /** Ореол в 1 клітинку навколо фігури (для «биття»). */
        void halo(int argb) {
            int[] src = px.clone();
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    if ((src[y * 16 + x] >>> 24) != 0) continue;
                    if (filled(src, x - 1, y) || filled(src, x + 1, y) || filled(src, x, y - 1) || filled(src, x, y + 1)) {
                        px[y * 16 + x] = argb;
                    }
                }
            }
        }

        private static boolean filled(int[] a, int x, int y) {
            return x >= 0 && x < 16 && y >= 0 && y < 16 && (a[y * 16 + x] >>> 24) != 0;
        }

        /** Виливає буфер: сусідні клітинки одного кольору зливаються в одну смугу. */
        void flush(GuiGraphics g) {
            for (int y = 0; y < 16; y++) {
                int x = 0;
                while (x < 16) {
                    int v = px[y * 16 + x];
                    if ((v >>> 24) == 0) { x++; continue; }
                    int x2 = x + 1;
                    while (x2 < 16 && px[y * 16 + x2] == v) x2++;
                    g.fill(x, y, x2, y + 1, v);
                    x = x2;
                }
            }
        }
    }

    private static final Cv CV = new Cv();
    private static final Cv TMP = new Cv();

    /**
     * @param x,y   лівий верхній кут іконки
     * @param unit  пікселів GUI на клітинку (іконка = 16·unit)
     * @param hover 0..1 — наведення: яскравіше й жвавіше
     */
    public static void draw(GuiGraphics g, Icon icon, int x, int y, int unit, int color, float hover) {
        double t = advance(icon, hover);
        int c = GeneratorFuelUiTheme.mix(color, 0xFFFFFFFF, 0.22f * hover);
        int dim = GeneratorFuelUiTheme.mix(c, 0xFF000000, 0.55f);
        int hi = GeneratorFuelUiTheme.mix(c, 0xFFFFFFFF, 0.6f);

        Cv cv = CV;
        cv.clear();
        switch (icon) {
            case PLAY -> play(cv, t, c, dim);
            case DOOR -> door(cv, t, c, dim, hi);
            case GEAR -> gear(cv, t, c, dim);
            case HEART -> heart(cv, c, dim, hi);
            case HOOK -> hook(cv, t, c, dim);
            case SHIELD -> shield(cv, t, c, dim, hi);
            case CLOCK -> clock(cv, t, c, dim, hi);
            case FLAME -> flame(cv, t, c, dim, hi);
            case TROPHY -> trophy(cv, t, c, dim, hi);
            case BLADE -> blade(cv, t, c, dim, hi);
            case FIST -> fist(cv, t, c, dim, hi);
            case CROWN -> crown(cv, t, c, dim, hi);
        }

        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0f);
        pose.scale(unit, unit, 1f);
        cv.flush(g);
        pose.popPose();
    }

    private static void play(Cv cv, double t, int c, int dim) {
        for (int i = 0; i < 6; i++) cv.r(4, 2 + i, 2 * (i + 1), 1, c);
        for (int i = 0; i < 6; i++) cv.r(4, 8 + i, 2 * (6 - i), 1, c);
        cv.r(4, 2, 2, 12, dim);
        // «Пульс»: ореол вмикається на піку синусоїди, а не плавне масштабування дробом.
        if (Math.sin(t * 3.0) > 0.55) cv.halo(GeneratorFuelUiTheme.alpha(c, 0.35f));
    }

    private static void door(Cv cv, double t, int c, int dim, int hi) {
        cv.art(1, 2, c, dim, hi,
            "#########",
            "#ooooooo#",
            "#ooooooo#",
            "#ooooooo#",
            "#oooooo+#",
            "#ooooooo#",
            "#ooooooo#",
            "#ooooooo#",
            "#ooooooo#",
            "#ooooooo#",
            "#ooooooo#",
            "#########");
        // Стрілка «вихід»: вилітає праворуч і гасне, потім з'являється знову.
        double ph = (t * 1.2) % 1.0;
        int ox = (int) (ph * 2.5);
        int a = GeneratorFuelUiTheme.alpha(hi, (float) (1.0 - ph * ph * 0.85));
        cv.r(10 + ox, 8, 4, 1, a);
        cv.r(12 + ox, 7, 1, 3, a);
        cv.r(11 + ox, 6, 1, 5, a);
    }

    /** Шестірня рахується по клітинках (полярні координати) — обертається чітко, без дробових матриць. */
    private static void gear(Cv cv, double t, int c, int dim) {
        final double step = Math.PI / 16.0;                 // крок повороту ≈ 11°
        double rot = Math.floor(t * 1.4 / step) * step;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double dx = x + 0.5 - 8.0, dy = y + 0.5 - 8.0;
                double r = Math.hypot(dx, dy);
                if (r < 2.4) { cv.set(x, y, HOLE); continue; }
                if (r < 3.4) { cv.set(x, y, dim); continue; }       // маточина навколо отвору
                boolean body = r <= 5.3;
                boolean tooth = r <= 7.4 && Math.cos(8.0 * (Math.atan2(dy, dx) - rot)) > 0.25;
                if (body || tooth) cv.set(x, y, c);
            }
        }
    }

    private static void heart(Cv cv, int c, int dim, int hi) {
        float beat = com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuTheme.beat(System.currentTimeMillis());
        int col = GeneratorFuelUiTheme.mix(c, hi, 0.35f * beat);
        cv.r(3, 3, 4, 1, col); cv.r(9, 3, 4, 1, col);
        cv.r(2, 4, 6, 1, col); cv.r(8, 4, 6, 1, col);
        cv.r(2, 5, 12, 2, col);
        cv.r(3, 7, 10, 1, col); cv.r(4, 8, 8, 1, col); cv.r(5, 9, 6, 1, col);
        cv.r(6, 10, 4, 1, col); cv.r(7, 11, 2, 1, col);
        cv.r(4, 5, 2, 1, hi);
        cv.r(3, 6, 1, 1, hi);
        if (beat > 0.3f) cv.halo(GeneratorFuelUiTheme.alpha(c, 0.45f * beat));
    }

    /** Гак гойдається маятником: рядки зсуваються пропорційно відстані від точки підвісу. */
    private static void hook(Cv cv, double t, int c, int dim) {
        cv.art(0, 0, c, dim, GeneratorFuelUiTheme.mix(c, 0xFFFFFFFF, 0.5f),
            "......####......",
            ".....##++##.....",
            ".....#....#.....",
            ".....##..##.....",
            "......####......",
            ".......#o.......",
            ".......#o.......",
            ".......#o.......",
            ".......#o.......",
            "..#....#o.......",
            "..##...#o.......",
            "..###..#o.......",
            "...##.###.......",
            "....#####.......",
            ".....###........");
        TMP.copySheared(cv, 1, Math.sin(t * 2.0) * 0.1);
        System.arraycopy(TMP.px, 0, cv.px, 0, 256);
    }

    private static void shield(Cv cv, double t, int c, int dim, int hi) {
        double ph = (t * 0.45) % 1.0;
        boolean hit = ph < 0.12;
        cv.r(3, 2, 10, 6, c);
        cv.r(4, 8, 8, 1, c); cv.r(5, 9, 6, 1, c); cv.r(6, 10, 4, 1, c); cv.r(7, 11, 2, 1, c);
        cv.r(7, 3, 2, 7, dim);
        cv.r(4, 5, 8, 2, dim);
        cv.r(3, 2, 10, 1, hi);
        if (hit) {
            cv.tint(0, 0, 16, 16, 0xFFFFFFFF, 0.3f);
            TMP.copyShifted(cv, ((int) (t * 40)) % 2 == 0 ? 1 : -1, 0);
            System.arraycopy(TMP.px, 0, cv.px, 0, 256);
        }
    }

    /** Секундна стрілка йде кроками (тік), а не плавно — на сітці так чистіше. */
    private static void clock(Cv cv, double t, int c, int dim, int hi) {
        for (int[] px : RING) cv.set(px[0], px[1], c);
        double a = Math.floor(t * 3.0) % 12 * (Math.PI / 6.0) - Math.PI / 2;
        for (int i = 1; i <= 5; i++) {
            cv.set((int) Math.floor(7.5 + Math.cos(a) * i), (int) Math.floor(7.5 + Math.sin(a) * i), hi);
        }
        double h = Math.PI / 6.0 * 2 - Math.PI / 2;
        for (int i = 1; i <= 3; i++) {
            cv.set((int) Math.floor(7.5 + Math.cos(h) * i), (int) Math.floor(7.5 + Math.sin(h) * i), c);
        }
        cv.r(7, 7, 2, 2, dim);
        cv.r(7, 1, 2, 1, hi);
    }

    /** Полум'я — краплина зі «язиком», що хитається; усередині менше, світліше. */
    private static void flame(Cv cv, double t, int c, int dim, int hi) {
        int frame = (int) (t * 7.0);
        double lean = ((frame % 4) == 0 || (frame % 4) == 2) ? 0.0 : ((frame % 4) == 1 ? 1.0 : -1.0);
        flameShape(cv, 1.0, 14.0, 5.9, lean, c, dim);
        flameShape(cv, 7.0, 14.0, 3.0, -lean * 0.6, hi, hi);
    }

    /** Краплина від {@code top} до {@code bottom}; найширша в нижній третині; кінчик зсувається на {@code lean}. */
    private static void flameShape(Cv cv, double top, double bottom, double maxHalf, double lean, int main, int edge) {
        for (int y = 0; y < 16; y++) {
            double tt = (y + 0.5 - top) / (bottom - top);
            if (tt <= 0 || tt >= 1) continue;
            double half = maxHalf * Math.pow(Math.sin(Math.PI * Math.pow(tt, 1.45)), 0.75);
            double cx = 7.5 + lean * Math.pow(1.0 - tt, 2.0) * 2.4;
            for (int x = 0; x < 16; x++) {
                if (Math.abs(x + 0.5 - cx) <= half) cv.set(x, y, main);
            }
        }
    }

    private static void trophy(Cv cv, double t, int c, int dim, int hi) {
        cv.r(4, 2, 8, 6, c);
        cv.r(5, 8, 6, 1, c);
        cv.r(2, 3, 2, 1, c); cv.r(2, 4, 1, 2, c); cv.r(3, 6, 1, 1, c);
        cv.r(12, 3, 2, 1, c); cv.r(13, 4, 1, 2, c); cv.r(12, 6, 1, 1, c);
        cv.r(7, 9, 2, 2, dim);
        cv.r(5, 11, 6, 2, c);
        cv.r(4, 2, 8, 1, hi);
        int k = (int) ((t * 6) % 12);
        if (k < 5) cv.set(5 + k, 3 + k, 0xFFFFFFFF);
    }

    /** Ніж по діагоналі: лезо до кінчика, гарда, руків'я; по лезу пробігає блиск. */
    private static void blade(Cv cv, double t, int c, int dim, int hi) {
        final double ax = 5.0, ay = 10.0, tx = 14.2, ty = 1.8;
        double len = Math.hypot(tx - ax, ty - ay);
        double dx = (tx - ax) / len, dy = (ty - ay) / len;
        double nx = -dy, ny = dx;
        double ph = (t * 0.9) % 1.7;
        double glint = ph <= 1.0 ? ph * len : -99;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double px = x + 0.5 - ax, py = y + 0.5 - ay;
                double s = px * dx + py * dy, u = px * nx + py * ny;
                if (s >= 0 && s <= len) {
                    double hw = 2.0 * Math.pow(1.0 - s / len, 0.8) + 0.35;
                    if (Math.abs(u) <= hw) {
                        int col = u < -0.2 ? hi : c;
                        if (Math.abs(s - glint) < 1.1) col = 0xFFFFFFFF;
                        cv.set(x, y, col);
                    }
                } else if (s >= -1.3 && s < 0 && Math.abs(u) <= 3.4) {
                    cv.set(x, y, hi);                                  // гарда
                } else if (s >= -5.6 && s < -1.3 && Math.abs(u) <= 1.1) {
                    cv.set(x, y, dim);                                 // руків'я
                }
            }
        }
    }

    /** Кулак: чотири кісточки, складені пальці, великий палець; «б'є» вперед і розкидає іскри. */
    private static void fist(Cv cv, double t, int c, int dim, int hi) {
        double s = Math.max(0.0, Math.sin(t * 4.0));
        int ox = (int) Math.round(s * 2);
        cv.art(1 + ox, 1, c, dim, hi,
            ".##.##.##.##.",
            "#++#++#++#++#",
            "#++#++#++#++#",
            "#oo#oo#oo#oo#",
            "#############",
            "#ooooooooooo#",
            "#############",
            "#+++++#######",
            "#++++oo######",
            ".#++oooo#####",
            ".###########.",
            "..ooooooooo..",
            "..ooooooooo..");
        if (ox >= 2) {
            cv.r(13, 0, 2, 1, hi); cv.r(15, 7, 1, 1, hi); cv.r(13, 14, 2, 1, hi);
        }
    }

    private static void crown(Cv cv, double t, int c, int dim, int hi) {
        cv.art(2, 2, c, dim, hi,
            ".....++.....",
            "#....##....#",
            "##...##...##",
            "###.####.###",
            "####.##.####",
            "############",
            "############",
            "############",
            "oooooooooooo",
            "oo+oo++oo+oo",
            "oooooooooooo");
        int x = (int) ((t * 8) % 24) - 4;
        if (x >= 2 && x <= 13) cv.tint(x, 7, 1, 3, 0xFFFFFFFF, 0.6f);
    }
}
