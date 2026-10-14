package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.client.vr.VrCompat;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.style.BloodFx;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * Екранний ефект «ти наступив на шипи» — лише для жертви.
 *
 * ── З чого складається (хронологія) ──────────────────────────────────
 *   0.00 с  біло-гарячий спалах (0.09 с) + червоний спалах, що гасне ~0.8 с;
 *   0.00 с  камеру різко КИДАЄ вгору й убік (удар знизу), FOV спершу
 *           розширюється, потім «стискається» від болю; далі згасаюче
 *           тремтіння тіла;
 *   0.02 с  ЗАЛІЗНІ ШИПИ вилітають знизу екрана — два ряди (задній темніший
 *           й нижчий, передній вищий) з гранями, сталевим градієнтом і
 *           закривавленими вістрями; вилітають з перельотом і тремтять;
 *   0.05 с  бризки крові на «лінзі» — великі й дрібні плями, що розтікаються;
 *   0.25 с  з верхнього краю повзуть патьоки крові з набряклими краплями;
 *   ~0.8 с  шипи повільно ховаються (стискаються вниз) і зникають;
 *   до 3 с  густа червона віньєтка пульсує як серцебиття, плями й патьоки
 *           поступово тьмяніють. Якщо удар збив із ніг ({@code downed}) —
 *           усе сильніше й довше.
 *
 * ── Чому так, а не готовою текстурою ─────────────────────────────────
 * Кожен удар випадковий: інша кількість, висота й нахил шипів, інші плями.
 * Тому шипи — це справжня трикутна геометрія (одна {@link Tesselator}-партія
 * на кадр), а кров — примітиви {@link BloodFx}, у стилі решти UI мода.
 * Жодних текстур і ресурсів, тож нічого не треба малювати вручну.
 *
 * Суто косметика: реальний напрямок погляду й сервер не змінюються (як і в
 * {@code ManiacHitEffect}, камера міняється лише через події Forge).
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SpikeHitEffect {

    /** Загальна тривалість (с) для звичайного удару; збивання з ніг множить на {@link #power}. */
    private static final float DURATION = 3.2f;

    private static final int FRONT_SPIKES = 11;
    private static final int BACK_SPIKES = 13;
    private static final int SPLATS = 12;
    private static final int DRIPS = 9;

    // ── Стан поточного ефекту ────────────────────────────────────────
    private static boolean active = false;
    private static long startNanos = 0L;
    private static float power = 1f;
    /** Напрямок нахилу камери — випадковий на кожен удар. */
    private static float side = 1f;

    private static Spike[] front = new Spike[0];
    private static Spike[] back = new Spike[0];
    private static Splat[] splats = new Splat[0];
    private static Drip[] drips = new Drip[0];

    private SpikeHitEffect() {}

    // ── Дані фігур (усе нормалізоване: частки ширини/висоти екрана) ──

    /** Один шип: центр по X, півширина основи, висота, нахил вістря, затримка вильоту. */
    private record Spike(float cx, float halfW, float height, float lean, float delay,
                         float hold, float bloodFrac, float tone) {}

    /** Пляма крові на «лінзі»: {@code shape} — радіальні множники контуру (нерівна клякса з «променями»). */
    private record Splat(float x, float y, float r, float delay, float life, int tone, float[] shape) {}

    private static final int SHAPE_N = 22;

    /** Патьок із верхнього краю. */
    private record Drip(float x, float width, float len, float delay, float grow, float phase) {}

    // ── Запуск / скидання ────────────────────────────────────────────

    public static void trigger(boolean downed) {
        VrCompat.haptic(VrCompat.Hand.MAIN, 0.3f, 0.9f);
        VrCompat.haptic(VrCompat.Hand.OFF, 0.3f, 0.9f);
        startNanos = System.nanoTime();
        active = true;
        power = downed ? 1.35f : 1.0f;

        Random rnd = new Random();
        side = rnd.nextBoolean() ? -1f : 1f;
        generate(rnd);

        // Короткий «крик» болю поверх світових звуків шипів (клієнтський UI-звук).
        Minecraft.getInstance().getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.PLAYER_HURT, 0.85f, 1.0f));
    }

    public static void reset() {
        active = false;
    }

    /** Розкладка шипів, плям і патьоків для цього удару. */
    private static void generate(Random rnd) {
        front = new Spike[FRONT_SPIKES];
        for (int i = 0; i < FRONT_SPIKES; i++) {
            float t = (i + 0.5f) / FRONT_SPIKES;
            float cx = t + (rnd.nextFloat() - 0.5f) * 0.05f;
            // Висота: центральні вищі (дзвін), краї нижчі, плюс шум.
            float bell = 1f - Math.abs(cx - 0.5f) * 1.5f;
            float height = (0.30f + 0.34f * bell) * (0.78f + rnd.nextFloat() * 0.34f);
            float halfW = 0.026f + rnd.nextFloat() * 0.016f;
            float lean = (rnd.nextFloat() - 0.5f) * 0.035f;
            float delay = Math.abs(cx - 0.5f) * 0.05f + rnd.nextFloat() * 0.025f;
            float hold = 0.65f + rnd.nextFloat() * 0.30f;
            front[i] = new Spike(cx, halfW, height, lean, delay, hold,
                0.40f + rnd.nextFloat() * 0.25f, 0.85f + rnd.nextFloat() * 0.15f);
        }
        back = new Spike[BACK_SPIKES];
        for (int i = 0; i < BACK_SPIKES; i++) {
            float t = (i + 0.2f + rnd.nextFloat() * 0.6f) / BACK_SPIKES;
            float bell = 1f - Math.abs(t - 0.5f) * 1.3f;
            float height = (0.18f + 0.22f * bell) * (0.75f + rnd.nextFloat() * 0.4f);
            back[i] = new Spike(t, 0.020f + rnd.nextFloat() * 0.012f, height,
                (rnd.nextFloat() - 0.5f) * 0.03f, 0.01f + rnd.nextFloat() * 0.03f,
                0.55f + rnd.nextFloat() * 0.25f, 0.30f + rnd.nextFloat() * 0.25f,
                0.45f + rnd.nextFloat() * 0.2f);
        }

        splats = new Splat[SPLATS];
        for (int i = 0; i < SPLATS; i++) {
            // Здебільшого нижня половина й краї — туди бризкає від шипів.
            float x = rnd.nextFloat();
            float y = 0.35f + rnd.nextFloat() * 0.65f;
            if (rnd.nextFloat() < 0.35f) y = rnd.nextFloat() * 0.5f;
            float r = 0.017f + rnd.nextFloat() * rnd.nextFloat() * 0.07f;
            if (i < 4) r += 0.028f;   // кілька великих, що одразу видно
            splats[i] = new Splat(x, y, r, 0.03f + rnd.nextFloat() * 0.12f,
                1.7f + rnd.nextFloat() * 1.2f, rnd.nextInt(3), makeShape(rnd));
        }

        drips = new Drip[DRIPS];
        for (int i = 0; i < DRIPS; i++) {
            float x = (i + 0.15f + rnd.nextFloat() * 0.7f) / DRIPS;
            drips[i] = new Drip(x, 0.004f + rnd.nextFloat() * 0.005f,
                0.14f + rnd.nextFloat() * 0.26f,
                0.22f + rnd.nextFloat() * 0.45f,
                1.4f + rnd.nextFloat() * 1.0f, rnd.nextFloat());
        }
    }

    /**
     * Контур клякси: нерівний «м'який» край + 3–5 довгих вузьких променів
     * (патьоки від удару) і западини по боках від них.
     */
    private static float[] makeShape(Random rnd) {
        float[] m = new float[SHAPE_N];
        for (int i = 0; i < SHAPE_N; i++) m[i] = 0.72f + rnd.nextFloat() * 0.3f;
        int rays = 3 + rnd.nextInt(3);
        for (int k = 0; k < rays; k++) {
            int i = rnd.nextInt(SHAPE_N);
            m[i] = 1.7f + rnd.nextFloat() * 1.3f;
            m[(i + 1) % SHAPE_N] = Math.min(m[(i + 1) % SHAPE_N], 0.6f);
            m[(i + SHAPE_N - 1) % SHAPE_N] = Math.min(m[(i + SHAPE_N - 1) % SHAPE_N], 0.6f);
        }
        return m;
    }

    // ── Час ──────────────────────────────────────────────────────────

    private static float elapsed() {
        if (!active) return Float.MAX_VALUE;
        float e = (System.nanoTime() - startNanos) / 1_000_000_000f;
        if (e > DURATION * power) active = false;
        return e;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float smooth(float a, float b, float v) {
        float t = clamp01((v - a) / (b - a));
        return t * t * (3f - 2f * t);
    }

    /** Швидкий виліт із перельотом (~15%) і м'якою посадкою. */
    private static float easeOutBack(float t) {
        float c1 = 1.9f, c3 = c1 + 1f;
        float k = t - 1f;
        return 1f + c3 * k * k * k + c1 * k * k;
    }

    private static int argb(float alpha, int r, int g, int b) {
        int a = Math.round(clamp01(alpha) * 255f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ── Екран ────────────────────────────────────────────────────────

    public static void render(GuiGraphics graphics) {
        if (!active) return;
        float e = elapsed();
        if (!active) return;

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        float life = clamp01(1f - e / (DURATION * power));

        // 1. Спалахи: біло-гарячий, потім червоний.
        float white = clamp01(1f - e / 0.09f) * 0.85f;
        if (white > 0f) graphics.fill(0, 0, w, h, argb(white, 255, 244, 236));
        float red = clamp01(1f - e / (0.8f * power)) * 0.55f * power;
        if (red > 0f) graphics.fill(0, 0, w, h, argb(red, 190, 0, 6));

        // 2. Затемнення болю й червона віньєтка з пульсом серця.
        float pulse = 0.80f + 0.20f * (float) Math.sin(e * (15f - 4f * (1f - life)));
        float vignette = (float) Math.pow(life, 1.15) * pulse * Math.min(1f, 0.9f * power);
        drawVignette(graphics, w, h, vignette, 0.45f * life);

        // 3. Бризки крові на «лінзі» (під шипами).
        drawSplats(graphics, w, h, e);

        // 4. Залізні шипи знизу: задній ряд, потім передній.
        drawSpikes(graphics, w, h, e);

        // 5. Патьоки зверху (поверх усього — кров стікає по «склу»).
        drawDrips(graphics, w, h, e);
    }

    // ── Шипи: одна трикутна партія на кадр ───────────────────────────

    private static void drawSpikes(GuiGraphics g, int w, int h, float e) {
        g.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f m = g.pose().last().pose();

        for (Spike s : back) spike(b, m, s, w, h, e, true);
        for (Spike s : front) spike(b, m, s, w, h, e, false);

        Tesselator.getInstance().end();
        RenderSystem.enableCull();
    }

    /**
     * Один шип: дві грані (ліва світліша, права темніша) зі сталевим градієнтом
     * «темна основа → світле вістря» + закривавлена верхівка. {@code rear} —
     * задній ряд: темніший, без крові.
     */
    private static void spike(BufferBuilder b, Matrix4f m, Spike s, int w, int h, float e, boolean rear) {
        float local = e - s.delay();
        if (local < 0f) return;

        // Виліт: швидко, з перельотом; потім утримання; потім повільне ховання.
        float rise = easeOutBack(clamp01(local / 0.085f));
        float holdEnd = s.hold() * power;
        float u = clamp01((local - holdEnd) / 0.95f);
        float retract = 1f - u * u * u;
        float alpha = 1f - smooth(0.6f, 1f, u);
        // Дрібна вібрація в мить удару: шипи «б'ються» в рамі.
        float shudder = (float) Math.sin(local * 85f) * 0.004f * (float) Math.exp(-local * 9f);

        float fHeight = Math.max(0f, rise * retract);
        if (fHeight <= 0.003f || alpha <= 0.01f) return;

        float baseY = h + 3f;
        float tipY = h - s.height() * h * fHeight * (rear ? 1f : power > 1f ? 1.12f : 1f);
        float cx = (s.cx() + shudder) * w;
        float hw = s.halfW() * w * (1f + 0.15f * (1f - fHeight));
        float tipX = cx + s.lean() * w * fHeight;

        float tone = s.tone();
        // Палітра граней: основа → вістря. Задній ряд — приглушений.
        int baseL = steel(0x16, 0x17, 0x1B, alpha * (rear ? 0.9f : 1f));
        int baseR = steel(0x0C, 0x0C, 0x10, alpha * (rear ? 0.9f : 1f));
        int tipL = steel(Math.round(0xD2 * tone), Math.round(0xD7 * tone), Math.round(0xE0 * tone), alpha);
        int tipR = steel(Math.round(0x8A * tone), Math.round(0x90 * tone), Math.round(0x9B * tone), alpha);

        float midX = cx + hw * 0.08f;
        // Ліва грань.
        tri(b, m, cx - hw, baseY, baseL, tipX, tipY, tipL, midX, baseY, baseL);
        // Права грань.
        tri(b, m, midX, baseY, baseR, tipX, tipY, tipR, cx + hw, baseY, baseR);

        if (rear) return;

        // Кров на верхівці: уздовж обох ребер від вістря вниз, згасає до сталі.
        float bf = s.bloodFrac() * clamp01((local - 0.06f) / 0.20f);
        if (bf <= 0.01f) return;
        float yB = tipY + (baseY - tipY) * bf;
        float lx = tipX + ((cx - hw) - tipX) * bf;
        float rx = tipX + ((cx + hw) - tipX) * bf;
        int hot = BloodFx.a(BloodFx.BRIGHT, alpha * 0.95f);
        int fade = BloodFx.a(BloodFx.DARK, 0f);
        // Один трикутник від вістря вниз по ребрах: яскрава кров → прозорість.
        tri(b, m, lx, yB, fade, tipX, tipY, hot, rx, yB, fade);
    }

    private static int steel(int r, int g, int bl, float alpha) {
        return argb(alpha, Math.min(255, r), Math.min(255, g), Math.min(255, bl));
    }

    private static void tri(BufferBuilder b, Matrix4f m,
                            float x1, float y1, int c1, float x2, float y2, int c2, float x3, float y3, int c3) {
        v(b, m, x1, y1, c1);
        v(b, m, x2, y2, c2);
        v(b, m, x3, y3, c3);
    }

    private static void v(BufferBuilder b, Matrix4f m, float x, float y, int argb) {
        b.vertex(m, x, y, 0f)
            .color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF)
            .endVertex();
    }

    // ── Кров на «лінзі» ──────────────────────────────────────────────

    private static void drawSplats(GuiGraphics g, int w, int h, float e) {
        float base = Math.min(w, h);
        g.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f m = g.pose().last().pose();

        for (int i = 0; i < splats.length; i++) {
            Splat s = splats[i];
            float local = e - s.delay();
            if (local < 0f) continue;
            // Різкий викид клякси (~0.09 с) і повільне тьмяніння.
            float spread = easeOutBack(clamp01(local / 0.09f));
            float fade = 1f - smooth(s.life() * 0.45f, s.life() * power, local);
            if (fade <= 0.01f) continue;
            int tone = s.tone() == 0 ? BloodFx.BRIGHT : s.tone() == 1 ? BloodFx.MID : BloodFx.DARK;
            int edge = BloodFx.a(tone, 0.85f * fade);
            // Центр темніший — об'єм без текстури.
            int core = BloodFx.a(BloodFx.mix(tone, BloodFx.DARK, 0.55f), 0.92f * fade);
            float r = s.r() * base * spread * (power > 1f ? 1.15f : 1f);
            float cx = s.x() * w, cy = s.y() * h;
            float rot = i * 1.7f;
            for (int k = 0; k < SHAPE_N; k++) {
                int k2 = (k + 1) % SHAPE_N;
                float a0 = rot + k * (float) (Math.PI * 2.0 / SHAPE_N);
                float a1 = rot + k2 * (float) (Math.PI * 2.0 / SHAPE_N);
                float r0 = r * s.shape()[k], r1 = r * s.shape()[k2];
                v(b, m, cx, cy, core);
                v(b, m, cx + (float) Math.cos(a0) * r0, cy + (float) Math.sin(a0) * r0, edge);
                v(b, m, cx + (float) Math.cos(a1) * r1, cy + (float) Math.sin(a1) * r1, edge);
            }
        }
        Tesselator.getInstance().end();
        RenderSystem.enableCull();

        // Дрібні краплі за кінцями променів — квадратики 2–3 px, без «бульбашок».
        for (int i = 0; i < splats.length; i++) {
            Splat s = splats[i];
            float local = e - s.delay();
            if (local < 0.04f) continue;
            float fade = 1f - smooth(s.life() * 0.45f, s.life() * power, local);
            if (fade <= 0.01f) continue;
            float r = s.r() * base * easeOutBack(clamp01(local / 0.09f));
            float cx = s.x() * w, cy = s.y() * h, rot = i * 1.7f;
            int col = BloodFx.a(BloodFx.MID, 0.9f * fade);
            for (int k = 0; k < SHAPE_N; k++) {
                if (s.shape()[k] < 1.8f) continue;
                float a = rot + k * (float) (Math.PI * 2.0 / SHAPE_N);
                float d = r * s.shape()[k] * 1.22f;
                int px = Math.round(cx + (float) Math.cos(a) * d);
                int py = Math.round(cy + (float) Math.sin(a) * d);
                int sz = r > 14f ? 3 : 2;
                g.fill(px, py, px + sz, py + sz, col);
            }
        }
    }

    private static void drawDrips(GuiGraphics g, int w, int h, float e) {
        long nowMs = System.currentTimeMillis();
        for (Drip d : drips) {
            float local = e - d.delay();
            if (local <= 0f) continue;
            // Повільне стікання з уповільненням ближче до кінця.
            float t = clamp01(local / d.grow());
            float eased = 1f - (1f - t) * (1f - t);
            float len = d.len() * h * eased;
            float fade = 1f - smooth(0.62f, 1f, e / (DURATION * power));
            if (len < 3f || fade <= 0.01f) continue;
            BloodFx.drip(g, Math.round(d.x() * w), -2, Math.max(2, Math.round(d.width() * w)),
                len, nowMs, d.phase(), 0.92f * fade);
        }
    }

    /** Червоні смуги від країв до центру + темна «тунельна» кайма болю. */
    private static void drawVignette(GuiGraphics g, int w, int h, float strength, float dark) {
        if (strength > 0.01f) {
            int steps = 36;
            int depthX = Math.round(w * 0.38f);
            int depthY = Math.round(h * 0.44f);
            for (int i = 0; i < steps; i++) {
                float t = i / (float) steps;
                float a = strength * (1f - t) * (1f - t);
                int color = argb(a, 175, 0, 4);
                int dx0 = Math.round(depthX * t), dx1 = Math.round(depthX * (i + 1f) / steps);
                int dy0 = Math.round(depthY * t), dy1 = Math.round(depthY * (i + 1f) / steps);
                g.fill(dx0, 0, dx1, h, color);
                g.fill(w - dx1, 0, w - dx0, h, color);
                g.fill(0, dy0, w, dy1, color);
                g.fill(0, h - dy1, w, h - dy0, color);
            }
        }
        if (dark > 0.01f) {
            // Вужча й темніша кайма: картинка ніби «стискається» від болю.
            int steps = 14;
            int depthX = Math.round(w * 0.16f);
            int depthY = Math.round(h * 0.2f);
            for (int i = 0; i < steps; i++) {
                float t = i / (float) steps;
                float a = dark * (1f - t) * (1f - t);
                int color = argb(a, 0, 0, 0);
                int dx0 = Math.round(depthX * t), dx1 = Math.round(depthX * (i + 1f) / steps);
                int dy0 = Math.round(depthY * t), dy1 = Math.round(depthY * (i + 1f) / steps);
                g.fill(dx0, 0, dx1, h, color);
                g.fill(w - dx1, 0, w - dx0, h, color);
                g.fill(0, dy0, w, dy1, color);
                g.fill(0, h - dy1, w, h - dy0, color);
            }
        }
    }

    // ── Камера ───────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (VrCompat.isVrActive()) return; // VR: кивок камери = нудота
        if (!active) return;
        float e = elapsed();
        if (!active) return;

        // Удар знизу: різкий кивок УГОРУ (негативний pitch) за ~0.05 с, далі
        // пружинить назад із легким недольотом.
        float up = e < 0.05f ? e / 0.05f : (float) Math.exp(-(e - 0.05f) * 5.2f);
        float pitchKick = -15f * power * up;
        float rebound = 3.2f * power * (float) (Math.exp(-e * 3.0) * Math.sin(e * 10.0));
        // Нахил убік, що повертається, і тремтіння тіла, що швидко згасає.
        float roll = side * 12f * power * (float) Math.exp(-e * 4.0);
        float tremor = 1.7f * power * (float) Math.exp(-e * 2.3);
        double t = e * 60.0;

        event.setPitch(event.getPitch() + pitchKick + rebound + (float) Math.sin(t * 0.91 + 1.0) * tremor);
        event.setRoll(event.getRoll() + roll + (float) Math.sin(t * 0.77) * tremor * 0.6f);
        event.setYaw(event.getYaw() + (float) Math.sin(t * 0.63 + 2.0) * tremor * 0.8f);
    }

    /** FOV: миттєве розширення від шоку, одразу за ним різке «стискання» від болю. */
    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!active) return;
        float e = elapsed();
        if (!active) return;
        double widen = 0.10 * Math.exp(-e * 30.0);
        double squeeze = 0.17 * power * (1.0 - Math.exp(-e * 45.0)) * Math.exp(-e * 6.0);
        event.setFOV(event.getFOV() * (1.0 + widen - squeeze));
    }
}
