package com.log_to_kot.maniacmod.client.barricade;

import com.log_to_kot.maniacmod.barricade.BarricadeScene;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Жива картинка барикади для міні-гри: «дірка», дошки (з реальною текстурою, розміром і поворотом),
 * цвяхи, їхня анімація (розхитування, виліт), провисання дошки на одному цвяху, падіння, тріски.
 * Нічого не вирішує — лише показує стан, який їй повідомляє екран ({@link #setPull},
 * {@link #markRemoved}). Той самий клас малює і справжню гру, і демо в туторіалі.
 *
 * <p>Сцена ({@link BarricadeScene}) — у блоках, Y вгору; тут вона переводиться в екранні
 * пікселі лінійним перетворенням {@link #layout}.
 */
@OnlyIn(Dist.CLIENT)
final class BarricadeSceneView {

    private static final int FRAME_COLOR = 0xFF2A3038;
    private static final int FRAME_BEVEL = 0xFF56606E;
    private static final int HOLE_EDGE = 0xFF040506;
    private static final int HOLE_DARK = 0xFF0A0C0B;
    private static final int HOLE_LIGHT = 0xFF66727F;

    private static final class NailState {
        boolean removed;
        float target;      // частка прогресу з сервера
        float shown;       // згладжена
        boolean held;
        float rate = 1f;
    }

    private static final class PlankState {
        int removedMask;
        float sag, sagVel, sagTarget;
        boolean falling, gone;
        float fallT, fx, fy, fvx, fvy, rot, rotVel;
        TextureAtlasSprite sprite;
    }

    private static final class Chip {
        float x, y, vx, vy, rot, vr, life, max, size, uF, vF;
        TextureAtlasSprite sprite;
    }

    private static final class Flying {
        float x, y, vx, vy, rot, vr, t;
    }

    final BarricadeScene scene;
    private final PlankState[] planks;
    private final NailState[] nails;
    private final int[] rank;
    private final List<Chip> chips = new ArrayList<>();
    private final List<Flying> flying = new ArrayList<>();
    private final Random rng = new Random(0xBA21CADEL);

    private float ox, oy, scale = 60f;
    private float time;
    private float open;          // згладжена «відкритість» дірки 0..1

    BarricadeSceneView(BarricadeScene scene) {
        this.scene = scene;
        this.planks = new PlankState[scene.plankCount()];
        for (int i = 0; i < planks.length; i++) planks[i] = new PlankState();
        this.nails = new NailState[scene.nailCount()];
        for (int i = 0; i < nails.length; i++) nails[i] = new NailState();
        int[] order = scene.drawOrder();
        this.rank = new int[planks.length];
        for (int i = 0; i < order.length; i++) rank[order[i]] = i;
        for (int i = 0; i < planks.length; i++) {
            planks[i].sprite = PlankRenderer.sprite(scene.planks().get(i).block());
        }
    }

    // ── Розкладка ────────────────────────────────────────────────────────

    /** Вписує сцену в прямокутник (з відступом) і повертає обраний масштаб (пікселів на блок). */
    float layout(float vx, float vy, float vw, float vh, float margin, float minScale, float maxScale) {
        double sw = Math.max(0.2, scene.width()), sh = Math.max(0.2, scene.height());
        float s = (float) Math.min((vw - 2 * margin) / sw, (vh - 2 * margin) / sh);
        scale = Math.max(minScale, Math.min(maxScale, s));
        double cx = (scene.minX() + scene.maxX()) / 2.0, cy = (scene.minY() + scene.maxY()) / 2.0;
        ox = vx + vw / 2f - (float) cx * scale;
        oy = vy + vh / 2f + (float) cy * scale;
        return scale;
    }

    float scale() { return scale; }

    private float sx(double x) { return ox + (float) x * scale; }

    private float sy(double y) { return oy - (float) y * scale; }

    /** Екранна X-координата цвяха (для курсора-лома тощо). */
    float nailX(int nail) { return sx(scene.nails().get(nail).x()); }

    float nailY(int nail) { return sy(scene.nails().get(nail).y()); }

    /** Екранна позиція ГОЛОВКИ цвяха (вона відходить від отвору, коли цвях тягнуть) — сюди чіпляється лом. */
    float nailHeadX(int nail) { return headPose(nail)[0]; }

    float nailHeadY(int nail) { return headPose(nail)[1]; }

    /** Згладжений прогрес цвяха 0..1 (для ефектів лома). */
    float nailPull(int nail) { return nails[nail].shown; }

    float hitRadius() { return Math.max(9f, Math.min(15f, scale * 0.08f)); }

    // ── Стан ─────────────────────────────────────────────────────────────

    boolean isRemoved(int nail) { return nails[nail].removed; }

    int nailsRemoved() {
        int n = 0;
        for (NailState s : nails) if (s.removed) n++;
        return n;
    }

    int planksGone() {
        int n = 0;
        for (PlankState p : planks) if (p.removedMask == 3) n++;
        return n;
    }

    boolean allGone() { return nailsRemoved() == nails.length; }

    void setPull(int nail, float fraction, boolean held, float rate) {
        if (nail < 0 || nail >= nails.length || nails[nail].removed) return;
        NailState n = nails[nail];
        n.target = Math.max(0f, Math.min(1f, fraction));
        n.held = held;
        n.rate = rate;
    }

    /** Цвях вийшов. {@code animate=false} — для відновлення стану при відкритті (без ефектів). */
    void markRemoved(int nail, boolean animate) {
        if (nail < 0 || nail >= nails.length || nails[nail].removed) return;
        NailState n = nails[nail];
        n.removed = true;
        n.held = false;
        n.target = 1f;
        n.shown = 1f;

        int p = nail / BarricadeScene.NAILS_PER_PLANK;
        int end = nail % BarricadeScene.NAILS_PER_PLANK;
        PlankState ps = planks[p];
        ps.removedMask |= 1 << end;

        BarricadeScene.Nail nl = scene.nails().get(nail);
        if (animate) {
            Flying f = new Flying();
            f.x = sx(nl.x());
            f.y = sy(nl.y());
            f.vx = (rng.nextFloat() - 0.7f) * 140f;
            f.vy = -120f - rng.nextFloat() * 60f;
            f.rot = (rng.nextFloat() - 0.5f) * 0.8f;
            f.vr = (rng.nextFloat() < 0.5f ? -1f : 1f) * (2.5f + rng.nextFloat() * 2.5f);   // кувиркається, а не крутиться як гвинт
            flying.add(f);
            burst(ps.sprite, f.x, f.y, 7, 70f);
        }

        if (ps.removedMask == 3) {
            if (animate) startFall(p); else { ps.gone = true; ps.falling = false; }
        } else {
            ps.sagTarget = sagFor(p, end);
            if (!animate) ps.sag = ps.sagTarget;
        }
    }

    /** Кут провисання дошки, що висить на одному цвяху ({@code removedEnd} — той, що вже вийшов). */
    private float sagFor(int plank, int removedEnd) {
        BarricadeScene.Plank pl = scene.planks().get(plank);
        BarricadeScene.Nail pivot = scene.nails().get(plank * 2 + (1 - removedEnd));
        BarricadeScene.Nail free = scene.nails().get(plank * 2 + removedEnd);
        double rx = free.x() - pivot.x();
        float mag = (float) Math.max(0.08, Math.min(0.30, 0.30 * 1.2 / Math.max(0.5, pl.length())));
        float sign;
        if (Math.abs(rx) < pl.length() * 0.2) sign = (plank % 2 == 0) ? 1f : -1f;   // майже вертикальна
        else sign = rx > 0 ? -1f : 1f;                                              // вільний кінець — вниз
        return sign * mag;
    }

    private BarricadeScene.Nail pivotNail(int plank) {
        PlankState ps = planks[plank];
        int removedEnd = (ps.removedMask & 1) != 0 ? 0 : 1;
        return scene.nails().get(plank * 2 + (1 - removedEnd));
    }

    private void startFall(int p) {
        PlankState ps = planks[p];
        ps.falling = true;
        ps.fallT = 0f;
        ps.fvx = (rng.nextFloat() - 0.5f) * 70f;
        ps.fvy = 30f;
        ps.rotVel = (rng.nextFloat() - 0.5f) * 2.6f;
        BarricadeScene.Plank pl = scene.planks().get(p);
        burst(ps.sprite, sx(pl.cx()), sy(pl.cy()), 16, 110f);
    }

    private void burst(TextureAtlasSprite sprite, float x, float y, int count, float speed) {
        if (sprite == null) return;
        for (int i = 0; i < count; i++) {
            Chip c = new Chip();
            c.sprite = sprite;
            c.x = x + (rng.nextFloat() - 0.5f) * 8f;
            c.y = y + (rng.nextFloat() - 0.5f) * 8f;
            double a = rng.nextDouble() * Math.PI * 2;
            float v = speed * (0.35f + rng.nextFloat() * 0.8f);
            c.vx = (float) Math.cos(a) * v;
            c.vy = (float) Math.sin(a) * v - speed * 0.4f;
            c.rot = rng.nextFloat() * 6f;
            c.vr = (rng.nextFloat() - 0.5f) * 14f;
            c.max = c.life = 0.55f + rng.nextFloat() * 0.5f;
            c.size = 2.5f + rng.nextFloat() * 3.5f;
            c.uF = rng.nextFloat();
            c.vF = rng.nextFloat();
            chips.add(c);
        }
    }

    // ── Закритість і вибір цвяха ─────────────────────────────────────────

    /** Цвях закритий дошкою, що лежить ПОВЕРХ його дошки (і ще не впала). */
    boolean covered(int nail) {
        int p = nail / BarricadeScene.NAILS_PER_PLANK;
        BarricadeScene.Nail n = scene.nails().get(nail);
        for (int q = 0; q < planks.length; q++) {
            if (q == p || rank[q] <= rank[p] || planks[q].gone || planks[q].falling) continue;
            BarricadeScene.Plank pl = scene.planks().get(q);
            double dx = n.x() - pl.cx(), dy = n.y() - pl.cy();
            double along = dx * Math.cos(pl.angle()) + dy * Math.sin(pl.angle());
            double across = -dx * Math.sin(pl.angle()) + dy * Math.cos(pl.angle());
            if (Math.abs(along) <= pl.length() / 2.0 && Math.abs(across) <= pl.width() / 2.0) return true;
        }
        return false;
    }

    /** Цвях, що доступний для хвата (не витягнутий, не закритий). */
    boolean available(int nail) {
        return nail >= 0 && nail < nails.length && !nails[nail].removed && !covered(nail);
    }

    /** Курсор поруч із цвяхом, який закритий іншою дошкою (для підказки «спершу зніми верхню»). */
    boolean nearCovered(double mx, double my) {
        float r = hitRadius() * 1.2f;
        for (BarricadeScene.Nail n : scene.nails()) {
            int i = n.index();
            if (nails[i].removed || !covered(i)) continue;
            if (Math.hypot(mx - sx(n.x()), my - sy(n.y())) <= r) return true;
        }
        return false;
    }

    /** Найближчий доступний цвях до курсора в межах радіуса, або −1. */
    int pick(double mx, double my, float radiusMul) {
        float best = hitRadius() * radiusMul;
        int found = -1;
        for (BarricadeScene.Nail n : scene.nails()) {
            int i = n.index();
            if (!available(i)) continue;
            float d = (float) Math.hypot(mx - sx(n.x()), my - sy(n.y()));
            if (d <= best) {
                best = d;
                found = i;
            }
        }
        return found;
    }

    // ── Оновлення ────────────────────────────────────────────────────────

    void update(float dt) {
        dt = Math.min(dt, 0.05f);
        time += dt;
        for (NailState n : nails) {
            if (n.removed) continue;
            n.shown += (n.target - n.shown) * Math.min(1f, dt * 18f);
        }
        for (PlankState p : planks) {
            if (p.gone) continue;
            if (p.falling) {
                p.fallT += dt;
                p.fvy += 1500f * dt;
                p.fx += p.fvx * dt;
                p.fy += p.fvy * dt;
                p.rot += p.rotVel * dt;
                if (p.fallT > 1.05f) { p.gone = true; p.falling = false; }
            } else {
                p.sagVel += (70f * (p.sagTarget - p.sag) - 7f * p.sagVel) * dt;
                p.sag += p.sagVel * dt;
            }
        }
        for (var it = chips.iterator(); it.hasNext(); ) {
            Chip c = it.next();
            c.life -= dt;
            if (c.life <= 0) { it.remove(); continue; }
            c.vy += 700f * dt;
            c.x += c.vx * dt;
            c.y += c.vy * dt;
            c.rot += c.vr * dt;
        }
        for (var it = flying.iterator(); it.hasNext(); ) {
            Flying f = it.next();
            f.t += dt;
            f.vy += 520f * dt;
            f.x += f.vx * dt;
            f.y += f.vy * dt;
            f.rot += f.vr * dt;
            if (f.t > 0.7f) it.remove();
        }
        float goal = planks.length == 0 ? 0f : (float) planksGone() / planks.length;
        open += (goal - open) * Math.min(1f, dt * 4f);
    }

    // ── Малювання ────────────────────────────────────────────────────────

    /**
     * @param hover   цвях під курсором (−1 — немає)
     * @param mine    цвях, який тримає ЦЕЙ гравець (−1 — жодного)
     * @param hintAll показувати тьмяні маркери на доступних цвяхах (підказка новачкам)
     */
    void render(GuiGraphics g, int hover, int mine, boolean hintAll) {
        renderHole(g);
        for (int idx : scene.drawOrder()) {
            renderPlank(g, idx);
            if (planks[idx].gone) continue;
            for (int e = 0; e < 2; e++) {
                int nail = idx * 2 + e;
                if (!nails[nail].removed) renderNail(g, nail, nail == hover, nail == mine, hintAll);
            }
        }
        for (Chip c : chips) {
            GuiShapes.chip(g, c.sprite, c.x, c.y, c.size, c.rot, c.uF, c.vF, Math.min(1f, c.life / c.max * 1.6f));
        }
        for (Flying f : flying) renderFlying(g, f);
    }

    // «Дірка»: рамка проріз + темна глибина, що світлішає, коли дошки падають.
    private void renderHole(GuiGraphics g) {
        List<float[]> pts = new ArrayList<>();
        float cx = 0, cy = 0;
        for (double[] p : scene.hole()) {
            float[] s = {sx(p[0]), sy(p[1])};
            pts.add(s);
            cx += s[0];
            cy += s[1];
        }
        if (pts.size() < 3) return;
        cx /= pts.size();
        cy /= pts.size();

        List<float[]> outer = offset(pts, cx, cy, Math.max(5f, scale * 0.07f));
        GuiShapes.fan(g, cx, cy, FRAME_COLOR, outer, FRAME_COLOR);
        for (int i = 0; i < outer.size(); i++) {
            float[] a = outer.get(i), b = outer.get((i + 1) % outer.size());
            GuiShapes.line(g, a[0], a[1], b[0], b[1], 1.2f, FRAME_BEVEL);
        }
        float breathe = 0.5f + 0.5f * (float) Math.sin(time * 1.6f);
        float glow = 0.10f + 0.80f * open + 0.04f * breathe;
        GuiShapes.fan(g, cx, cy, GuiShapes.mix(HOLE_DARK, HOLE_LIGHT, glow), pts,
            GuiShapes.mix(HOLE_EDGE, HOLE_LIGHT, glow * 0.45f));
    }

    private static List<float[]> offset(List<float[]> pts, float cx, float cy, float d) {
        int n = pts.size();
        List<float[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float[] p = pts.get((i + n - 1) % n), v = pts.get(i), q = pts.get((i + 1) % n);
            float[] n1 = outward(p, v, cx, cy), n2 = outward(v, q, cx, cy);
            float bx = n1[0] + n2[0], by = n1[1] + n2[1];
            float bl = (float) Math.hypot(bx, by);
            if (bl < 1e-4f) { bx = n1[0]; by = n1[1]; bl = 1f; }
            bx /= bl;
            by /= bl;
            float miter = Math.min(2f, 1f / Math.max(0.5f, bx * n1[0] + by * n1[1]));
            out.add(new float[] {v[0] + bx * d * miter, v[1] + by * d * miter});
        }
        return out;
    }

    private static float[] outward(float[] a, float[] b, float cx, float cy) {
        float ex = b[0] - a[0], ey = b[1] - a[1];
        float len = (float) Math.hypot(ex, ey);
        if (len < 1e-4f) return new float[] {0, 0};
        float nx = ey / len, ny = -ex / len;
        float mx = (a[0] + b[0]) / 2f - cx, my = (a[1] + b[1]) / 2f - cy;
        if (nx * mx + ny * my < 0) { nx = -nx; ny = -ny; }
        return new float[] {nx, ny};
    }

    private void renderPlank(GuiGraphics g, int idx) {
        PlankState st = planks[idx];
        if (st.gone) return;
        BarricadeScene.Plank pl = scene.planks().get(idx);
        float len = (float) (pl.length() * scale), wid = (float) (pl.width() * scale);
        float cx = sx(pl.cx()), cy = sy(pl.cy());
        float alpha = st.falling ? Math.max(0f, st.fallT < 0.45f ? 1f : 1f - (st.fallT - 0.45f) / 0.6f) : 1f;
        if (alpha <= 0f) return;

        // Скільки цвяхів ще тримається + поточне розхитування: від них залежить глибина тіні.
        float lift = 0f;
        for (int e = 0; e < 2; e++) lift = Math.max(lift, nails[idx * 2 + e].removed ? 0f : nails[idx * 2 + e].shown);
        float shadow = 2.2f + 3f * lift + (st.falling ? 6f : 0f);

        g.pose().pushPose();
        if (st.falling) {
            g.pose().translate(st.fx, st.fy, 0f);
            g.pose().translate(cx, cy, 0f);
            g.pose().mulPose(Axis.ZP.rotation(-st.rot));
            g.pose().translate(-cx, -cy, 0f);
        }
        if (st.removedMask != 0 && st.removedMask != 3) {
            BarricadeScene.Nail pv = pivotNail(idx);
            float px = sx(pv.x()), py = sy(pv.y());
            g.pose().translate(px, py, 0f);
            g.pose().mulPose(Axis.ZP.rotation(-st.sag));
            g.pose().translate(-px, -py, 0f);
        } else if (st.falling) {
            // Падаюча дошка лишає позу, в якій була; провисання вже зафіксоване в sag.
            float sag = st.sag;
            g.pose().translate(cx, cy, 0f);
            g.pose().mulPose(Axis.ZP.rotation(-sag * 0.5f));
            g.pose().translate(-cx, -cy, 0f);
        }

        // Тінь від дошки на «дірку».
        g.pose().pushPose();
        g.pose().translate(cx + shadow * 0.6f, cy + shadow, 0f);
        g.pose().mulPose(Axis.ZP.rotation(-(float) pl.angle()));
        GuiShapes.rect(g, -len / 2f - 0.5f, -wid / 2f - 0.5f, len / 2f + 0.5f, wid / 2f + 0.5f,
            GuiShapes.withAlpha(0xFF000000, 0.42f * alpha));
        g.pose().popPose();

        // Сама дошка.
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0f);
        g.pose().mulPose(Axis.ZP.rotation(-(float) pl.angle()));
        int tint = GuiShapes.withAlpha(0xFFFFFFFF, alpha);
        if (st.sprite != null) {
            GuiShapes.plankTexture(g, st.sprite, len, wid, scale, pl.grainOffset(), pl.grainAcross(), tint);
        }
        float h = wid / 2f, l = len / 2f;
        float edge = Math.max(1f, wid * 0.09f);
        GuiShapes.gradientRect(g, -l, -h, l, h,
            GuiShapes.withAlpha(0x22FFFFFF, alpha), GuiShapes.withAlpha(0x22FFFFFF, alpha),
            GuiShapes.withAlpha(0x55000000, alpha), GuiShapes.withAlpha(0x55000000, alpha));
        GuiShapes.rect(g, -l, -h, l, -h + edge, GuiShapes.withAlpha(0x40FFFFFF, alpha));
        GuiShapes.rect(g, -l, h - edge * 1.3f, l, h, GuiShapes.withAlpha(0x60000000, alpha));
        GuiShapes.rect(g, -l, -h, -l + 1.2f, h, GuiShapes.withAlpha(0x50000000, alpha));
        GuiShapes.rect(g, l - 1.2f, -h, l, h, GuiShapes.withAlpha(0x50000000, alpha));

        // Дірки від вже витягнутих цвяхів.
        for (int e = 0; e < 2; e++) {
            if (!nails[idx * 2 + e].removed) continue;
            BarricadeScene.Nail n = scene.nails().get(idx * 2 + e);
            double dx = n.x() - pl.cx(), dy = n.y() - pl.cy();
            float along = (float) ((dx * Math.cos(pl.angle()) + dy * Math.sin(pl.angle())) * scale);
            float across = (float) ((-dx * Math.sin(pl.angle()) + dy * Math.cos(pl.angle())) * scale);
            float r = Math.max(1.6f, scale * 0.026f);
            // Локальна Y вниз по екрану; «вгору» сцени — мінус.
            GuiShapes.disk(g, along, -across, r * 1.5f, GuiShapes.withAlpha(0x55000000, alpha), GuiShapes.withAlpha(0x00000000, alpha));
            GuiShapes.disk(g, along, -across, r, GuiShapes.withAlpha(0xFF0B0907, alpha), GuiShapes.withAlpha(0xFF241B13, alpha));
        }
        g.pose().popPose();
        g.pose().popPose();
    }

    /** Наскільки цвях вийшов із дошки при pull = 1, у радіусах капелюшка. */
    private static final float NAIL_REACH = 4.6f;
    /** Нахил цвяха при pull = 1 (рад): лом тягне його не прямо, а важелем, тож він хилиться. */
    private static final float NAIL_LEAN = 0.62f;

    /**
     * Поза цвяха на екрані: {головка x, головка y, нахил (рад), довжина стрижня (px)}.
     * Цвях не крутиться, а ВИХОДИТЬ: стрижень росте з отвору, нахиляється під дією важеля, а
     * капелюшок (спершу видно «в лоб» як круг) повертається ребром.
     */
    private float[] headPose(int nail) {
        NailState ns = nails[nail];
        BarricadeScene.Nail pos = scene.nails().get(nail);
        float x = sx(pos.x()), y = sy(pos.y());
        float r = Math.max(2.8f, scale * 0.04f);
        float pull = ns.shown;
        float lean = pull * NAIL_LEAN;
        float shake = ns.held ? (0.35f + 1.3f * pull) : 0f;
        // Розхитування ВЗДОВЖ важеля (туди-сюди), а не обертання.
        float wob = shake * (float) Math.sin(time * 47f + nail * 3.1f) * 0.045f;
        float ang = lean + wob;
        float len = pull * r * NAIL_REACH;
        float hx = x - (float) Math.sin(ang) * len;
        float hy = y - (float) Math.cos(ang) * len;
        if (ns.held) hy += 0.6f * shake * (float) Math.cos(time * 59f + nail * 1.7f);
        return new float[] {hx, hy, ang, len};
    }

    /**
     * Цвях, забитий в дошку: спочатку лише плоский капелюшок («в лоб»). Коли його тягнуть, із дерева
     * показується металевий СТРИЖЕНЬ, цвях хилиться, а капелюшок повертається ребром — отримуємо
     * класичне «Т» витягнутого цвяха. Жодного обертання навколо осі (воно й робило схожість на гвинт).
     */
    private void renderNail(GuiGraphics g, int nail, boolean hovered, boolean mine, boolean hintAll) {
        NailState ns = nails[nail];
        BarricadeScene.Nail pos = scene.nails().get(nail);
        float x = sx(pos.x()), y = sy(pos.y());
        float pull = ns.shown;
        float r = Math.max(2.8f, scale * 0.04f);
        float[] hp = headPose(nail);
        float hx = hp[0], hy = hp[1], ang = hp[2];
        float hr = r * (1f + 0.30f * pull);                   // трохи ближче до глядача — трохи більший
        float side = smoothstep(0.04f, 0.42f, pull);          // 0 — капелюшок «в лоб», 1 — ребром

        // Вм'ятина в дереві; коли цвях пішов — темний отвір із розщепленим деревом.
        GuiShapes.disk(g, x, y, r * 2.1f, 0x55000000, 0x00000000);
        if (pull > 0.03f) {
            GuiShapes.disk(g, x, y, r * (0.75f + 0.55f * pull), 0xFF0B0907, 0xFF2A1F15);
        }

        if (pull > 0.03f) {
            // Тінь стрижня й капелюшка на дошці (світло зверху-ліворуч).
            float sh = 1.5f + 3.5f * pull;
            GuiShapes.line(g, x + 1f, y + 1.2f, hx + sh, hy + sh * 1.2f, Math.max(1.4f, r * 0.55f), 0x55000000);

            // Стрижень: темні краї, світла середина, до кінця звужується не потрібно — видно лише частину.
            float sw = Math.max(1.7f, r * 0.58f);
            GuiShapes.line(g, x, y, hx, hy, sw, 0xFF2B2E33);
            float nx = (float) Math.cos(ang) * -0.45f, ny = (float) Math.sin(ang) * 0.45f;
            GuiShapes.line(g, x + nx, y + ny, hx + nx, hy + ny, sw * 0.45f, 0xFFA7ADB5);
        }

        // Тінь капелюшка.
        GuiShapes.disk(g, hx + 1.2f + 3.2f * pull, hy + 1.6f + 4f * pull, hr * 1.02f, 0x58000000, 0x00000000);

        // Капелюшок: еліпс, перпендикулярний стрижню; ребром — тонка пластинка.
        g.pose().pushPose();
        g.pose().translate(hx, hy, 0f);
        g.pose().mulPose(Axis.ZP.rotation(-ang));
        g.pose().scale(1f + 0.25f * side, 1f - 0.62f * side, 1f);
        GuiShapes.disk(g, 0f, 0f, hr + 1f, 0xFF16171A, 0xFF16171A);
        GuiShapes.disk(g, 0f, 0f, hr, 0xFFB4B9C0, 0xFF5A6068);
        GuiShapes.disk(g, -hr * 0.18f, -hr * 0.2f, hr * 0.5f, 0xB0E6E9EC, 0x00B4B9C0);
        g.pose().popPose();

        if (covered(nail)) return;   // закритий цвях лишається під дошкою, що малюється далі

        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 4f + nail);
        if (!ns.held && !mine) {
            if (hovered) {
                GuiShapes.ring(g, x, y, hitRadius() * 0.62f, 1.6f, 1f,
                    GuiShapes.withAlpha(0xFFF2F4F8, 0.6f + 0.35f * pulse));
            } else if (hintAll) {
                GuiShapes.ring(g, x, y, hitRadius() * 0.52f, 1.2f, 1f,
                    GuiShapes.withAlpha(0xFFC2C9D6, 0.14f + 0.12f * pulse));
            }
        }
        if (pull > 0.02f && (ns.held || mine || pull > 0.05f)) drawPullBar(g, x, y + r * 2.2f + 9f, pull, mine, ns);
    }

    private static float smoothstep(float a, float b, float v) {
        float t = Math.max(0f, Math.min(1f, (v - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    /** Компактна шкала витягування під цвяхом. */
    private void drawPullBar(GuiGraphics g, float cx, float y, float pull, boolean mine, NailState ns) {
        float w = Math.max(30f, scale * 0.32f), h = 4f;
        float x0 = cx - w / 2f;
        int fill = GuiShapes.mix(0xFFC2C9D6, 0xFFE0A030, pull);
        if (ns.rate > 1.05f) fill = GuiShapes.mix(fill, 0xFFB0242F, Math.min(0.7f, (ns.rate - 1f) * 0.7f));
        if (!mine && !ns.held) fill = 0xFF7E8896;     // розхитаний, але зараз ніхто не тягне
        GuiShapes.rect(g, x0 - 1f, y - 1f, x0 + w + 1f, y + h + 1f, 0xCC000000);
        GuiShapes.rect(g, x0, y, x0 + w, y + h, 0xFF1C2026);
        GuiShapes.rect(g, x0, y, x0 + w * Math.min(1f, pull), y + h, fill);
        GuiShapes.rect(g, x0, y, x0 + w * Math.min(1f, pull), y + 1f, 0x55FFFFFF);
    }

    /** Цвях, що вилетів: «Т» зі стрижнем і гострим кінцем, кувиркається й летить до глядача. */
    private void renderFlying(GuiGraphics g, Flying f) {
        float q = Math.min(1f, f.t / 0.7f);
        float sc = 1f + 1.6f * q;
        float r = Math.max(2.6f, scale * 0.042f) * sc;
        float a = 1f - q * q;
        g.pose().pushPose();
        g.pose().translate(f.x, f.y, 0f);
        g.pose().mulPose(Axis.ZP.rotation(f.rot));
        float shaft = r * 4.2f;
        GuiShapes.line(g, 0f, r * 0.3f, 0f, shaft, Math.max(1.6f, r * 0.55f), GuiShapes.withAlpha(0xFF2B2E33, a));
        GuiShapes.line(g, -0.4f, r * 0.3f, -0.4f, shaft, Math.max(0.8f, r * 0.25f), GuiShapes.withAlpha(0xFFA7ADB5, a));
        GuiShapes.line(g, 0f, shaft, 0f, shaft + r * 0.7f, Math.max(0.8f, r * 0.3f), GuiShapes.withAlpha(0xFF6B7078, a));
        GuiShapes.rect(g, -r * 1.2f, -r * 0.2f, r * 1.2f, r * 0.5f, GuiShapes.withAlpha(0xFF16171A, a));
        GuiShapes.rect(g, -r * 1.1f, -r * 0.1f, r * 1.1f, r * 0.4f, GuiShapes.withAlpha(0xFFB4B9C0, a));
        g.pose().popPose();
    }
}
