package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.client.vr.VrCompat;

import com.log_to_kot.maniacmod.ManiacMod;
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
 * Потужний екранний ефект «тебе вдарив маньяк» — лише для жертви.
 *
 * ── З чого складається ───────────────────────────────────────────────
 *   1. Білий спалах на 0.1 с — «удар у голову»;
 *   2. червоний спалах на весь екран, що гасне за ~0.6 с;
 *   3. густа червона віньєтка по краях, що пульсує як серцебиття й
 *      тримається ~2.5 с;
 *   4. удар камери: різкий нахил (roll), кивок (pitch) і тряска, що
 *      швидко згасає;
 *   5. «стиск» поля зору (FOV) у момент удару;
 *   6. ванільний звук удару.
 *
 * Якщо удар збив із ніг ({@code downed}) — усе сильніше й довше.
 *
 * Суто косметика: реальний напрямок погляду й сервер не змінюються
 * (як і в {@code WireShockCameraShake}, працює через події камери).
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ManiacHitEffect {

    private static final float DURATION = 2.6f;

    private static long startNanos = 0L;
    private static boolean active = false;
    private static float power = 1f;
    /** Напрямок нахилу камери — випадковий на кожен удар. */
    private static float side = 1f;

    private ManiacHitEffect() {}

    public static void trigger(boolean downed) {
        VrCompat.haptic(VrCompat.Hand.MAIN, downed ? 0.5f : 0.25f, 1.0f);
        VrCompat.haptic(VrCompat.Hand.OFF, downed ? 0.5f : 0.25f, 1.0f);
        startNanos = System.nanoTime();
        active = true;
        power = downed ? 1.35f : 1.0f;
        side = Math.random() < 0.5 ? -1f : 1f;

        Minecraft mc = Minecraft.getInstance();
        // Ванільний звук удару гравця. Раніше поверх нього грав ще й
        // GENERIC_EXPLODE із заниженим тоном — саме він звучав як грім/блискавка.
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_ATTACK_STRONG, 1.0f, 1.0f));
    }

    public static void reset() {
        active = false;
    }

    private static float elapsed() {
        if (!active) return Float.MAX_VALUE;
        float e = (System.nanoTime() - startNanos) / 1_000_000_000f;
        if (e > DURATION * power) active = false;
        return e;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
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

        // 1–2. Спалахи на весь екран.
        float white = clamp01(1f - e / 0.10f) * 0.85f;
        if (white > 0f) graphics.fill(0, 0, w, h, argb(white, 255, 255, 255));
        float red = clamp01(1f - e / (0.6f * power)) * 0.6f * power;
        if (red > 0f) graphics.fill(0, 0, w, h, argb(red, 200, 0, 0));

        // 3. Віньєтка з пульсом.
        float life = clamp01(1f - e / (DURATION * power));
        float pulse = 0.85f + 0.15f * (float) Math.sin(e * 14f);
        float vignette = (float) Math.pow(life, 1.3) * pulse * Math.min(1f, 0.85f * power);
        drawVignette(graphics, w, h, vignette);
    }

    /** Червоні смуги від країв до центру: альфа спадає в глибину. */
    private static void drawVignette(GuiGraphics g, int w, int h, float strength) {
        if (strength <= 0.01f) return;
        int steps = 36;
        int depthX = Math.round(w * 0.36f);
        int depthY = Math.round(h * 0.42f);
        for (int i = 0; i < steps; i++) {
            float t = i / (float) steps;                 // 0 — край, 1 — глибина
            float a = strength * (1f - t) * (1f - t);
            int color = argb(a, 170, 0, 0);
            int dx0 = Math.round(depthX * t), dx1 = Math.round(depthX * (i + 1f) / steps);
            int dy0 = Math.round(depthY * t), dy1 = Math.round(depthY * (i + 1f) / steps);
            g.fill(dx0, 0, dx1, h, color);               // ліво
            g.fill(w - dx1, 0, w - dx0, h, color);       // право
            g.fill(0, dy0, w, dy1, color);               // верх
            g.fill(0, h - dy1, w, h - dy0, color);       // низ
        }
    }

    // ── Камера ───────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (VrCompat.isVrActive()) return; // VR: кивок камери = нудота
        if (!active) return;
        float e = elapsed();
        if (!active) return;

        float decay = (float) Math.exp(-e * 3.6f);
        double t = e * Math.PI * 2.0;

        // Різкий нахил убік і кивок, що плавно повертаються, + хаотична тряска.
        float roll = side * 16f * power * decay;
        float pitchKick = -9f * power * (float) Math.exp(-e * 6f);
        float shake = 3.4f * power * decay;

        event.setRoll(event.getRoll() + roll + (float) Math.sin(t * 9.0) * shake * 0.6f);
        event.setPitch(event.getPitch() + pitchKick + (float) Math.sin(t * 11.0 + 1.0) * shake);
        event.setYaw(event.getYaw() + (float) Math.sin(t * 7.0 + 2.0) * shake);
    }

    /** «Стиск» FOV у мить удару — картинка різко наїжджає й повертається. */
    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!active) return;
        float e = elapsed();
        if (!active) return;
        float squeeze = 0.22f * power * (float) Math.exp(-e * 8f);
        event.setFOV(event.getFOV() * (1.0 - squeeze));
    }
}
