package com.log_to_kot.maniacmod.client.style;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Кров для UI: «завіса», що стікає з верхнього краю екрана, патьоки,
 * краплі, що набухають, відриваються й падають, та калюжки внизу. Усе
 * малюється лише {@code GuiGraphics.fill/fillGradient} — без текстур і
 * шейдерів, тож працює в будь-якому екрані мода.
 *
 * ── Чому окремо від ManiacUiTheme ────────────────────────────────────
 * {@link ManiacUiTheme} — безстанова палітра панелей. Кров — це ЖИВИЙ
 * стан (довжина патьоків, краплі в польоті), тому вона тримається
 * в екземплярі {@link Curtain}, який належить екрану: екран відкрився —
 * кров почала стікати, закрився — кров втягнулась угору й зникла.
 * Жодної статики: два екрани поспіль мають власні, незалежні завіси.
 *
 * ── Що малює {@link Curtain} ─────────────────────────────────────────
 *   1. неперервний край «калюжі» вгорі: плавна крива в 1 px-стовпчиках
 *      (сума синусів), що біля кожного патьока здувається горбком —
 *      патьок виглядає витягнутим із калюжі, а не приклеєним до неї;
 *   2. патьоки: нерівномірні (більшість коротких, кілька довгих), мають
 *      розтруб у корені, трохи звужуються до кінця й закінчуються
 *      набряклою краплею; перед відривом крапля набухає;
 *   3. краплі: відриваються зі спокою, падають з прискоренням у формі
 *      сльозинки, патьок після відриву коротко «сіпається» вгору;
 *   4. внизу крапля лишає плоску калюжку, що повільно тьмяніє.
 *
 * ── Чому переписано ─────────────────────────────────────────────────
 * Перша версія малювала бахрому 3-px стовпчиками з яскравим нижнім
 * краєм (читалось як гребінець/штрихкод), патьоки — рівними брусками
 * з градієнтом до неонового й «ліхтариком», що біжить тілом, краплі —
 * з комето-подібним прозорим хвостом, а бризки — прямокутниками.
 */
public final class BloodFx {

    private BloodFx() {}

    // ── Палітра ─────────────────────────────────────────────────────────
    public static final int DARK   = 0xFF3A0006;
    public static final int MID    = 0xFF7E0C12;
    public static final int BRIGHT = 0xFFC8161F;
    public static final int HOT    = 0xFFFF4A52;

    // Тіла патьоків: один колір + темний край + вузький блік. Раніше тіло
    // було градієнтом MID→BRIGHT і виходило «неонове до кінця»; рідка кров
    // має рівний колір, а об'єм дають лише край і блік.
    private static final int POOL      = 0xFF5A070C;
    private static final int BODY      = 0xFF8E0F16;
    private static final int BODY_DK   = 0xFF42050A;
    private static final int GLINT     = 0xFFE0434C;

    // ── Колір ───────────────────────────────────────────────────────────

    /** Множить альфу ARGB на {@code mul} (0..1), RGB не чіпає. */
    public static int a(int argb, float mul) {
        int al = Math.round(((argb >>> 24) & 0xFF) * Mth.clamp(mul, 0f, 1f));
        return (al << 24) | (argb & 0x00FFFFFF);
    }

    /** Лінійне змішування двох ARGB (разом з альфою). */
    public static int mix(int c0, int c1, float t) {
        float k = Mth.clamp(t, 0f, 1f);
        int a = Math.round(((c0 >>> 24) & 0xFF) * (1f - k) + ((c1 >>> 24) & 0xFF) * k);
        int r = Math.round(((c0 >> 16) & 0xFF) * (1f - k) + ((c1 >> 16) & 0xFF) * k);
        int g = Math.round(((c0 >> 8) & 0xFF) * (1f - k) + ((c1 >> 8) & 0xFF) * k);
        int b = Math.round((c0 & 0xFF) * (1f - k) + (c1 & 0xFF) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Детермінований шум 0..1 із 64-бітного насіння (без {@code Random}-алокацій). */
    public static float hash01(long v) {
        long x = v;
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return (x >>> 40) / (float) (1L << 24);
    }

    /** Рамка 1 px навколо прямокутника. */
    public static void border1(GuiGraphics g, int x, int y, int w, int h, int argb) {
        if ((argb >>> 24) == 0) return;
        g.fill(x, y, x + w, y + 1, argb);
        g.fill(x, y + h - 1, x + w, y + h, argb);
        g.fill(x, y + 1, x + 1, y + h - 1, argb);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
    }

    // ── Примітиви ───────────────────────────────────────────────────────

    /** Круглий згусток: заповнені горизонтальні смуги. */
    public static void blob(GuiGraphics g, float cx, float cy, float r, int argb) {
        if (r <= 0f || (argb >>> 24) == 0) return;
        int ir = (int) Math.ceil(r);
        int icy = Math.round(cy);
        for (int dy = -ir; dy <= ir; dy++) {
            float half = (float) Math.sqrt(Math.max(0f, r * r - dy * dy));
            int x0 = Math.round(cx - half);
            int x1 = Math.round(cx + half);
            if (x1 <= x0) x1 = x0 + 1;
            g.fill(x0, icy + dy, x1, icy + dy + 1, argb);
        }
    }

    /**
     * Один патьок, що стікає ВНИЗ від {@code (x, y0)}: рівне тіло з темним
     * правим краєм і вузьким бліком ліворуч, кінець злегка звужений, на
     * кінці — набрякла крапля з бліком. {@code phase} 0..1 лише розводить
     * дихання сусідів. Підпис лишився тим самим, що й раніше — його
     * викликають екрани меню й оцінок.
     */
    public static void drip(GuiGraphics g, int x, int y0, int w, float len,
                            long nowMs, float phase, float alpha) {
        drip(g, x, y0, w, len, nowMs, phase, alpha, false, 0f);
    }

    /**
     * @param flare чи розтрубом розширювати корінь (патьок виходить із калюжі,
     *              а не з голого краю)
     * @param swell 0..1 — наскільки крапля на кінці набухла перед відривом
     */
    static void drip(GuiGraphics g, int x, int y0, int w, float len,
                     long nowMs, float phase, float alpha, boolean flare, float swell) {
        int l = Math.round(len);
        if (l < 2 || alpha <= 0.01f) return;

        float breathe = 0.12f * (float) Math.sin(nowMs / 1100.0 + phase * 6.28);
        float stem = w * 0.5f;
        float cx = x + w * 0.5f;
        // Радіус цибулини: помітно ширша за патьок, але не куля — вона плавно
        // витягується з «шийки», а не насаджена на паличку.
        float R = Math.max(2.6f, stem + 1.7f + swell * 1.5f + breathe);
        float funnel = R * 3.2f;          // довжина «лійки» від тіла до круглого дна
        float cy = l;                      // центр круглого дна
        int rows = (int) Math.ceil(l + R);

        int body = a(BODY, alpha);
        int dark = a(BODY_DK, alpha);
        int glint = a(GLINT, alpha * 0.5f);

        for (int row = 0; row < rows; row++) {
            float hw = stem;
            if (flare && row < 7) {
                float k = 1f - row / 7f;
                hw += 2.4f * k * k;
            }
            boolean bulb = false;
            float rr = row + 0.5f;
            if (rr > cy - funnel) {
                if (rr <= cy) {
                    // Лійка: ширина росте повільно, а ближче до дна — швидше (t^2),
                    // тож силует вгнутий, як у справжньої краплі, а не «куля на паличці».
                    float t = UiAnim.clamp01((rr - (cy - funnel)) / funnel);
                    hw = Math.max(hw, stem + (R - stem) * t * t);
                    bulb = t > 0.55f;
                } else {
                    float dy = rr - cy;
                    hw = (float) Math.sqrt(Math.max(0f, R * R - dy * dy));
                    bulb = true;
                }
            }
            if (bulb) spanFlat(g, cx, y0 + row, hw, body, dark, rr > cy);
            else spanRow(g, cx, y0 + row, hw, body, dark, glint, true);
        }

        // Блік на цибулині: маленька світла цятка, зсунута вгору-вліво.
        float bx = cx - R * 0.40f;
        float by = l - R * 0.45f;
        g.fill(Math.round(bx), Math.round(by), Math.round(bx) + 1, Math.round(by) + 2,
            a(GLINT, alpha * 0.85f));
    }

    /**
     * Один піксельний рядок тіла зі згладженими краями: крайні пікселі
     * малюються з альфою, що дорівнює частці покриття. Без цього ширина
     * стрибала цілими пікселями й кінець патьока виходив сходинками.
     * Темний правий і світлий лівий піксель дають об'єм лише на тілі
     * ({@code shade}); на цибулині їх нема — там суцільна заливка.
     */
    private static void spanRow(GuiGraphics g, float cx, int y, float hw,
                                int body, int dark, int glint, boolean shade) {
        if (hw < 0.2f) return;
        float l = cx - hw;
        float r = cx + hw;
        int il = (int) Math.floor(l);
        int ir = (int) Math.ceil(r);
        int fl = (int) Math.ceil(l);      // перший повністю покритий піксель
        int fr = (int) Math.floor(r);     // перший піксель ПІСЛЯ повністю покритих
        if (fr > fl) g.fill(fl, y, fr, y + 1, body);
        if (fl > il) g.fill(il, y, il + 1, y + 1, a(body, Math.min(1f, fl - l)));
        if (ir > fr && fr >= fl - 0 && r > fr) g.fill(fr, y, fr + 1, y + 1, a(body, Math.min(1f, r - fr)));
        if (shade && fr - fl >= 3) {
            g.fill(fr - 1, y, fr, y + 1, dark);
            g.fill(fl + 1, y, fl + 2, y + 1, glint);
        }
    }

    /** Рядок цибулини/краплі: суцільний, лише нижня половина має темнішу праву межу. */
    private static void spanFlat(GuiGraphics g, float cx, int y, float hw, int body, int dark, boolean lower) {
        spanRow(g, cx, y, hw, body, dark, 0, false);
        if (lower && hw >= 1.5f) {
            int fr = (int) Math.floor(cx + hw);
            g.fill(fr - 1, y, fr, y + 1, a(dark, 0.55f));
        }
    }

    /**
     * Крапля в польоті: гострий верх, що тягнеться вгору, і кругле дно —
     * класична сльозинка. {@code s} — радіус круглої частини.
     */
    private static void teardrop(GuiGraphics g, float cx, float cy, float s, float stretch, float alpha) {
        int body = a(BODY, alpha);
        int dark = a(BODY_DK, alpha);
        int glint = a(GLINT, alpha * 0.5f);
        float top = cy - s * (1.6f + stretch);
        int y0 = Math.round(top);
        int y1 = Math.round(cy + s);
        for (int y = y0; y <= y1; y++) {
            float yy = y + 0.5f;
            float hw;
            if (yy <= cy) {
                float t = (yy - top) / Math.max(1f, cy - top);   // 0 у вістрі, 1 на екваторі
                hw = s * (float) Math.pow(UiAnim.clamp01(t), 1.7);
            } else {
                float dy = yy - cy;
                hw = (float) Math.sqrt(Math.max(0f, s * s - dy * dy));
            }
            spanFlat(g, cx, y, Math.max(0.45f, hw), body, dark, yy > cy);
        }
        if (s >= 1.6f) g.fill(Math.round(cx - s * 0.4f), Math.round(cy - s * 0.2f),
            Math.round(cx - s * 0.4f) + 1, Math.round(cy - s * 0.2f) + 1, a(GLINT, alpha * 0.8f));
    }

    // ── Завіса ──────────────────────────────────────────────────────────

    /**
     * Повна «кровʼяна завіса» екрана. Створюється разом з екраном,
     * {@link #render} викликається щокадру ДО малювання інтерфейсу.
     */
    public static final class Curtain {

        private final long seed;
        private final long openedAt;
        private final Random rng;

        private final List<Runner> runners = new ArrayList<>();
        private final List<Drop> drops = new ArrayList<>();
        private final List<Splat> splats = new ArrayList<>();
        private float[] bump = new float[0];

        private int builtW = -1;
        private int builtH = -1;
        private long lastMs;

        // Фази кривої краю калюжі — з насіння, щоб кожне відкриття мало свою форму.
        private final float p1;
        private final float p2;
        private final float p3;
        private final float p4;

        public Curtain(long seed, long openedAtMs) {
            this.seed = seed;
            this.openedAt = openedAtMs;
            this.rng = new Random(seed);
            this.lastMs = openedAtMs;
            this.p1 = hash01(seed + 11L) * 6.28f;
            this.p2 = hash01(seed + 12L) * 6.28f;
            this.p3 = hash01(seed + 13L) * 6.28f;
            this.p4 = hash01(seed + 14L) * 6.28f;
        }

        private static final class Runner {
            int x;
            int w;
            float maxLen;
            float delay;
            float phase;
            float creepRate;
            long dropAt;
            long nextDrop;
        }

        private static final class Drop {
            float x;
            float y;
            float vy;
            float size;
        }

        private static final class Splat {
            float x;
            float size;
            long bornAt;
        }

        private void rebuild(int w, int h) {
            builtW = w;
            builtH = h;
            runners.clear();
            bump = new float[w + 1];

            // Нерівномірне розташування: крок має великий розкид, тож патьоки
            // збираються в купки з прогалинами, а не стоять «через рівні проміжки».
            float cursor = 6f + hash01(seed + 1L) * 24f;
            int i = 0;
            while (cursor < w - 6) {
                long s = seed * 31L + i * 7919L;
                Runner r = new Runner();
                float kw = hash01(s + 1);
                r.w = kw < 0.30f ? 3 : kw < 0.65f ? 4 : kw < 0.90f ? 5 : 6;
                r.x = Math.round(cursor);
                // Більшість коротких, кілька довгих.
                float k = hash01(s + 3);
                r.maxLen = h * (0.04f + 0.46f * k * k * k) + 8f;
                r.delay = 150f + hash01(s + 4) * 1100f;
                r.phase = hash01(s + 5);
                r.creepRate = 0.0015f + 0.0035f * hash01(s + 7);
                r.nextDrop = openedAt + 3000L + (long) (hash01(s + 6) * 6000f);
                runners.add(r);
                cursor += 22f + hash01(s + 8) * hash01(s + 9) * 120f + r.w;
                i++;
            }
        }

        /**
         * @param life 1 = повна завіса, 0 = повністю втягнута вгору (анімація закриття)
         */
        public void render(GuiGraphics g, int w, int h, long now, float life) {
            if (w != builtW || h != builtH) rebuild(w, h);

            float dt = Mth.clamp((now - lastMs) / 1000f, 0f, 0.05f);
            lastMs = now;
            if (life <= 0.01f) return;

            float age = now - openedAt;

            // Спершу рахуємо стан патьоків: від їхньої довжини залежить горбок калюжі.
            java.util.Arrays.fill(bump, 0f);
            int n = runners.size();
            float[] lens = new float[n];
            float[] swells = new float[n];
            for (int i = 0; i < n; i++) {
                Runner r = runners.get(i);
                float ra = age - r.delay;
                float grow = ra <= 0f ? 0f : UiAnim.easeOutCubic(ra / (1000f + r.maxLen * 6f));
                float creep = Math.min(r.maxLen * 0.12f, Math.max(0f, ra - 3000f) * r.creepRate);
                float recoil = 0f;
                if (r.dropAt > 0L) {
                    recoil = (2f + r.w * 0.6f) * (float) Math.exp(-(now - r.dropAt) / 480f);
                }
                float len = (r.maxLen * grow + creep - recoil) * life;
                lens[i] = len;

                // Набухання краплі перед відривом: плавно за 1.6 с до nextDrop.
                float sw = 0f;
                if (grow >= 0.98f && len > 10f) {
                    float u = UiAnim.clamp01((now - (r.nextDrop - 1600L)) / 1600f);
                    sw = u * u * (3f - 2f * u);
                }
                swells[i] = sw;

                float gate = UiAnim.clamp01(grow * 3f) * life;
                float sigma = r.w * 2.6f + 7f;
                int lo = Math.max(0, (int) (r.x - sigma * 3f));
                int hi = Math.min(w - 1, (int) (r.x + r.w + sigma * 3f));
                for (int xx = lo; xx <= hi; xx++) {
                    float d = (xx - (r.x + r.w * 0.5f)) / sigma;
                    bump[xx] += (5.0f + r.w * 1.1f) * gate * (float) Math.exp(-d * d);
                }
            }

            // 1) край калюжі: 1-px стовпчики, суміжні однакової висоти зливаємо.
            int poolBody = a(POOL, 0.97f);
            int rim = a(BODY, 0.97f);
            int gloss = a(GLINT, 0.28f);
            int runStart = 0;
            int runH = -1;
            for (int xx = 0; xx <= w; xx++) {
                int hgt = xx < w ? poolHeight(xx, w, age, now, life) : -2;
                if (xx == 0) { runH = hgt; continue; }
                if (hgt == runH) continue;
                if (runH > 0) {
                    g.fill(runStart, 0, xx, runH, poolBody);
                    if (runH >= 4) {
                        g.fill(runStart, runH - 3, xx, runH, rim);
                        g.fill(runStart, runH - 2, xx, runH - 1, gloss);
                    }
                }
                runStart = xx;
                runH = hgt;
            }

            // 2) патьоки
            for (int i = 0; i < n; i++) {
                Runner r = runners.get(i);
                float len = lens[i];
                if (len < 2f) continue;

                drip(g, r.x, 0, r.w, len, now, r.phase, 0.97f, true, swells[i]);

                float ra = age - r.delay;
                boolean grown = ra > 1000f + r.maxLen * 6f;
                if (grown && life > 0.9f && now >= r.nextDrop) {
                    if (len > 10f) {
                        Drop d = new Drop();
                        d.x = r.x + r.w * 0.5f;
                        d.y = len + r.w * 0.5f + 1.5f;
                        d.vy = 12f;
                        d.size = 1.4f + r.w * 0.28f;
                        drops.add(d);
                        r.dropAt = now;
                        r.nextDrop = now + 5000L + (long) (rng.nextFloat() * 8000f);
                    } else {
                        r.nextDrop = now + 1500L;
                    }
                }
            }

            // 3) краплі в польоті: сльозинка з коротким хвостом, без «комети».
            Iterator<Drop> di = drops.iterator();
            while (di.hasNext()) {
                Drop d = di.next();
                d.vy += 520f * dt;
                d.y += d.vy * dt;
                if (d.y >= h - 3) {
                    Splat s = new Splat();
                    s.x = d.x;
                    s.size = d.size;
                    s.bornAt = now;
                    splats.add(s);
                    di.remove();
                    continue;
                }
                float stretch = UiAnim.clamp01(d.vy / 420f) * 1.6f;
                teardrop(g, d.x, d.y, d.size + 0.4f, stretch, life);
            }

            // 4) калюжки внизу: плоска, розтікається і тьмяніє.
            Iterator<Splat> si = splats.iterator();
            while (si.hasNext()) {
                Splat s = si.next();
                float t = (now - s.bornAt) / 2200f;
                if (t >= 1f) {
                    si.remove();
                    continue;
                }
                float grow = UiAnim.easeOutCubic(t * 3.5f);
                float fade = 1f - UiAnim.clamp01((t - 0.35f) / 0.65f);
                float al = fade * life;
                int wd = Math.round(5f + s.size * 5f * grow);
                int cx = Math.round(s.x);
                g.fill(cx - wd / 2, h - 1, cx + (wd + 1) / 2, h, a(BODY, al));
                int w2 = Math.max(2, Math.round(wd * 0.62f));
                g.fill(cx - w2 / 2, h - 2, cx + (w2 + 1) / 2, h - 1, a(BODY, al));
                int w3 = Math.max(1, Math.round(wd * 0.28f));
                g.fill(cx - w3 / 2, h - 3, cx + (w3 + 1) / 2, h - 2, a(BODY_DK, al));
                g.fill(cx - w2 / 2, h - 2, cx - w2 / 2 + 1, h - 1, a(GLINT, al * 0.5f));
            }
        }

        /** Висота краю калюжі в стовпчику {@code x}: плавна крива + горбки біля патьоків. */
        private int poolHeight(int x, int w, float age, long now, float life) {
            float base = 10.5f
                + 4.2f * (float) Math.sin(x * 0.016f + p1)
                + 2.6f * (float) Math.sin(x * 0.043f + p2)
                + 1.0f * (float) Math.sin(x * 0.101f + p3);
            float breathe = 0.7f * (float) Math.sin(now / 950.0 + x * 0.021f);
            float wave = 0.5f + 0.5f * (float) Math.sin(x * 0.013f + p4);
            float grow = UiAnim.easeOutCubic((age - wave * 450f) / 750f);
            float hgt = (Math.max(5f, base + breathe) + bump[x]) * grow * life;
            return Math.round(hgt);
        }
    }
}
