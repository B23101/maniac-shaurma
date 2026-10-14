package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.vr.VrCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Екранний ефект «ти влучив» — ЛИШЕ для маньяка (жертва має свій — {@link ManiacHitEffect}).
 *
 * ── З чого складається ───────────────────────────────────────────────
 *   1. хрест-маркер влучання на прицілі: чотири діагональні штрихи, що розходяться
 *      і гаснуть (білий → червоний), як хітмаркер у шутерах;
 *   2. короткий червоний відблиск по краях екрана (набагато легший, ніж у жертви);
 *   3. легкий поштовх камери вниз і «наїзд» FOV — відчуття, що кулак вдарив у ціль;
 *   4. глухий звук влучання.
 *
 * Якщо удар збив жертву з ніг ({@code downed}) — маркер більший, червоний,
 * з другим кільцем штрихів, а поштовх сильніший.
 *
 * Суто косметика: сервер і реальний напрямок погляду не змінюються.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ManiacHitConfirmFx {

    private static final float DURATION = 0.55f;

    private static long startNanos = 0L;
    private static boolean active = false;
    private static boolean downed = false;

    private ManiacHitConfirmFx() {}

    public static void trigger(boolean knockedDown) {
        startNanos = System.nanoTime();
        active = true;
        downed = knockedDown;
        VrCompat.haptic(VrCompat.Hand.MAIN, knockedDown ? 0.6f : 0.4f, 1.0f);
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
            knockedDown ? SoundEvents.PLAYER_ATTACK_CRIT : SoundEvents.PLAYER_ATTACK_KNOCKBACK,
            0.9f, knockedDown ? 0.8f : 1.0f));
    }

    public static void reset() {
        active = false;
    }

    private static float elapsed() {
        if (!active) return Float.MAX_VALUE;
        float e = (System.nanoTime() - startNanos) / 1_000_000_000f;
        if (e > DURATION) active = false;
        return e;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int argb(float alpha, int r, int g, int b) {
        return (Math.round(clamp01(alpha) * 255f) << 24) | (r << 16) | (g << 8) | b;
    }

    // ── Екран ────────────────────────────────────────────────────────

    public static void render(GuiGraphics g) {
        if (!active) return;
        float e = elapsed();
        if (!active) return;

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        float t = clamp01(e / DURATION);
        float fade = 1f - t;

        // 2. Легкий червоний відблиск по краях.
        float edge = fade * fade * (downed ? 0.55f : 0.38f);
        drawEdgeGlow(g, w, h, edge);

        // 1. Хітмаркер: штрихи розходяться від центру й гаснуть.
        int cx = w / 2, cy = h / 2;
        float grow = 1f - (1f - t) * (1f - t);            // швидко → повільно
        int inner = Math.round(3 + grow * (downed ? 7 : 5));
        int len = Math.round((downed ? 9 : 7) * (1f - 0.3f * t));
        float a = clamp01(fade * 1.4f);
        // Колір: від білого до червоного.
        int gb = Math.round(255 * clamp01(1f - t * 2.2f) + (downed ? 30 : 60) * clamp01(t * 2.2f));
        int color = argb(a, 255, gb, gb);
        int shadow = argb(a * 0.8f, 20, 0, 0);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                diagonal(g, cx, cy, sx, sy, inner, len, 1, shadow, 1);
                diagonal(g, cx, cy, sx, sy, inner, len, 0, color, 0);
            }
        }
        if (downed) {
            // Друге, зовнішнє кільце штрихів — «нокдаун».
            int in2 = inner + len + 3;
            int len2 = Math.max(3, len - 3);
            int col2 = argb(a * 0.9f, 255, 40, 40);
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sy = -1; sy <= 1; sy += 2)
                    diagonal(g, cx, cy, sx, sy, in2, len2, 0, col2, 0);
        }
    }

    /** Діагональний штрих (по пікселях) від {@code inner} до {@code inner+len} від центру. */
    private static void diagonal(GuiGraphics g, int cx, int cy, int sx, int sy,
                                 int inner, int len, int pad, int color, int shadowOffset) {
        for (int i = 0; i < len; i++) {
            int d = inner + i;
            int x = cx + sx * d + shadowOffset;
            int y = cy + sy * d + shadowOffset;
            int x0 = sx > 0 ? x : x - 1;
            int y0 = sy > 0 ? y : y - 1;
            g.fill(x0 - pad, y0 - pad, x0 + 1 + pad, y0 + 1 + pad, color);
        }
    }

    private static void drawEdgeGlow(GuiGraphics g, int w, int h, float strength) {
        if (strength <= 0.01f) return;
        int steps = 14;
        int depthX = Math.round(w * 0.12f);
        int depthY = Math.round(h * 0.16f);
        for (int i = 0; i < steps; i++) {
            float k = i / (float) steps;
            int color = argb(strength * (1f - k) * (1f - k), 200, 20, 20);
            int dx0 = Math.round(depthX * k), dx1 = Math.round(depthX * (i + 1f) / steps);
            int dy0 = Math.round(depthY * k), dy1 = Math.round(depthY * (i + 1f) / steps);
            g.fill(dx0, 0, dx1, h, color);
            g.fill(w - dx1, 0, w - dx0, h, color);
            g.fill(0, dy0, w, dy1, color);
            g.fill(0, h - dy1, w, h - dy0, color);
        }
    }

    // ── Камера ───────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (VrCompat.isVrActive()) return; // VR: поштовх камери = нудота
        if (!active) return;
        float e = elapsed();
        if (!active) return;
        float kick = (downed ? 3.2f : 2.0f) * (float) Math.exp(-e * 11f);
        event.setPitch(event.getPitch() + kick);
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!active) return;
        float e = elapsed();
        if (!active) return;
        float squeeze = (downed ? 0.10f : 0.06f) * (float) Math.exp(-e * 9f);
        event.setFOV(event.getFOV() * (1.0 - squeeze));
    }
}
