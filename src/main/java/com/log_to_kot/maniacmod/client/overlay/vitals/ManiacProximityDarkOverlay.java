package com.log_to_kot.maniacmod.client.overlay.vitals;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.HeartbeatClock;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Затемнення країв екрана, коли маніяк зовсім поруч. Живиться тим самим
 * {@code ClientMatchState.heartbeat()} (0..1, квадрат від відстані), що й звук серця:
 * від {@link #START} (~5 блоків при радіусі 12) краї починають темніти, на {@link #FULL}
 * (~1.5 блока) тьмяніє й увесь кадр. Чорне, не червоне — червона віньєтка лишається за малим хп.
 *
 * <p>Поява й зникнення плавні (згладжування по реальному часу), а глибина ледь «дихає» в такт
 * {@link HeartbeatClock#pulse()}, тож затемнення й глибоке серце відчуваються як одне ціле.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacProximityDarkOverlay {

    private ManiacProximityDarkOverlay() {}

    private static final float START = 0.30f;
    private static final float FULL = 0.75f;

    /** Альфа (0..255) на самому краю при повній силі й загальне притемнення центру. */
    private static final int EDGE_ALPHA = 235;
    private static final int VEIL_ALPHA = 70;

    private static final float DEPTH_X = 0.40f;
    private static final float DEPTH_Y = 0.52f;
    private static final float FALLOFF = 1.5f;
    private static final int STEP = 2;

    /** Швидкість згладжування, 1/с: повільна поява (≈0.7 с), трохи швидший відхід. */
    private static final float RISE = 2.2f;
    private static final float FALL = 3.2f;

    private static float level = 0f;
    private static long lastNanos = 0L;

    public static void render(GuiGraphics g) {
        long nowN = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min(0.1f, (nowN - lastNanos) / 1_000_000_000f);
        lastNanos = nowN;

        float target = 0f;
        if (ClientMatchState.isSurvivor() && ClientMatchState.allows(PhaseRule.SURVIVOR_VITALS)) {
            SurvivorState st = ClientMatchState.survivorState();
            if (st != null && !st.isTerminal()) {
                float x = Math.max(0f, Math.min(1f, (ClientMatchState.heartbeat() - START) / (FULL - START)));
                target = x * x * (3f - 2f * x);   // smoothstep
            }
        }
        float rate = target > level ? RISE : FALL;
        level += (target - level) * (1f - (float) Math.exp(-rate * dt));
        if (level < 0.004f && target <= 0f) { level = 0f; return; }

        // Дихання в такт серцю: до ±10% глибини.
        float breathe = 1f - 0.10f * (1f - HeartbeatClock.pulse());
        float k = level * breathe;
        int edge = Math.min(255, Math.round(EDGE_ALPHA * k));
        int veil = Math.round(VEIL_ALPHA * level * level);
        if (edge <= 0 && veil <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int dx = Math.round(w * DEPTH_X * (0.6f + 0.4f * level));
        int dy = Math.round(h * DEPTH_Y * (0.6f + 0.4f * level));

        g.pose().pushPose();
        g.pose().translate(0, 0, 1850);   // під червоною віньєткою хп (1900)
        if (veil > 0) g.fill(0, 0, w, h, veil << 24);
        edge(g, w, h, dx, edge, 0);
        edge(g, w, h, dx, edge, 1);
        edge(g, w, h, dy, edge, 2);
        edge(g, w, h, dy, edge, 3);
        g.pose().popPose();
    }

    private static void edge(GuiGraphics g, int w, int h, int depth, int base, int side) {
        if (base <= 0) return;
        for (int o = 0; o < depth; o += STEP) {
            float t = (float) o / depth;
            int a = Math.round(base * (float) Math.pow(1f - t, FALLOFF));
            if (a <= 0) break;
            int c = a << 24;
            int end = Math.min(o + STEP, depth);
            switch (side) {
                case 0 -> g.fill(o, 0, end, h, c);
                case 1 -> g.fill(w - end, 0, w - o, h, c);
                case 2 -> g.fill(0, o, w, end, c);
                default -> g.fill(0, h - end, w, h - o, c);
            }
        }
    }
}
