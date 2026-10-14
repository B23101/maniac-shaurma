package com.log_to_kot.maniacmod.client.hooks;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.overlay.WorldToScreen;
import com.log_to_kot.maniacmod.net.s2c.hooks.HookHighlightPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Екранні мітки вільних гаків — ТІЛЬКИ маньяку, що несе жертву.
 *
 * Контур сутності працює лише там, де її знає клієнт (view-distance сервера), тому на великій
 * відстані (~400 блоків) підказкою служить ця мітка: проєкція {@link WorldToScreen} видна крізь
 * стіни, з будь-якої відстані, притискається до краю екрана, якщо гак за спиною.
 */
@OnlyIn(Dist.CLIENT)
public final class HookMarker {

    private static final int EDGE_MARGIN = 26;
    private static final int RADIUS = 6;
    private static final int RGB = 0xC060FF;

    private HookMarker() {}

    public static void render(GuiGraphics graphics) {
        if (!ClientMatchState.isManiac()) return;
        var hooks = ClientHooks.freeHooks();
        if (hooks.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        double pulse = 0.80 + 0.20 * Math.sin(System.currentTimeMillis() / 320.0);
        int alpha = (int) Math.max(80, Math.min(255, 255 * pulse));

        for (HookHighlightPacket.Entry hook : hooks) {
            WorldToScreen.Point point = WorldToScreen.project(hook.x(), hook.y() + 3.2, hook.z(),
                width, height, EDGE_MARGIN);
            if (point == null) continue;

            int cx = point.x(), cy = point.y();
            drawDiamond(graphics, cx, cy, RADIUS + 1, (alpha << 24) | 0x101010);
            drawDiamond(graphics, cx, cy, RADIUS, (alpha << 24) | RGB);
            // Літера-«гачок»: вертикаль + дуга.
            int ink = (alpha << 24) | 0xFFFFFF;
            graphics.fill(cx, cy - 4, cx + 1, cy + 2, ink);
            graphics.fill(cx - 2, cy + 2, cx + 1, cy + 3, ink);
            graphics.fill(cx - 2, cy, cx - 1, cy + 3, ink);

            int meters = (int) Math.round(WorldToScreen.distanceXZ(hook.x(), hook.z()));
            graphics.drawCenteredString(mc.font, meters + " м", cx, cy + RADIUS + 5,
                (Math.max(140, alpha) << 24) | RGB);
        }
    }

    private static void drawDiamond(GuiGraphics graphics, int cx, int cy, int radius, int argb) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = radius - Math.abs(dy);
            graphics.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, argb);
        }
    }
}
