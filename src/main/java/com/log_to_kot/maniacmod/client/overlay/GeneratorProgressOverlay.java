package com.log_to_kot.maniacmod.client.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;

/**
 * Overlay ремонту генератора.
 *
 * Показує прогрес-бар одразу під прицілом:
 *
 *   ┌────────────────────────────────┐
 *   │  ████████████░░░░░░  68%       │   ← кольоровий бар
 *   │      Етап 2 / 5                │   ← підпис
 *   └────────────────────────────────┘
 *
 * Викликається з ClientSetup через RegisterGuiOverlaysEvent.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class GeneratorProgressOverlay {

    private static int  progress = -1; // -1 = схований
    private static int  stage    = 0;
    private static int  stages   = 0;

    /** Викликається з GeneratorProgressPacket.handle() */
    public static void setProgress(int p, int s, int total) {
        progress = p;
        stage    = s;
        stages   = total;
    }

    /** Викликається з ClientSetup (RegisterGuiOverlaysEvent). */
    public static void onRenderOverlay(GuiGraphics g) {
        if (progress < 0) return;

        Minecraft mc = Minecraft.getInstance();

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        int barW  = 120;
        int barH  = 8;
        int x     = sw / 2 - barW / 2;
        int y     = sh / 2 + 16;   // трохи нижче прицілу

        // Фон
        g.fill(x - 1, y - 1, x + barW + 1, y + barH + 1, 0xAA000000);

        // Порожній бар (темний)
        g.fill(x, y, x + barW, y + barH, 0xFF333333);

        // Заповнений бар (жовто-зелений)
        int filled = barW * progress / 100;
        int color  = progress < 40 ? 0xFFFFAA00 : 0xFF55FF55;
        if (filled > 0)
            g.fill(x, y, x + filled, y + barH, color);

        // Відсоток та етап
        String label = progress + "%   " + stage + " / " + stages;
        g.drawString(mc.font, label, x + barW / 2 - mc.font.width(label) / 2,
                     y + barH + 3, 0xFFFFFFFF, true);
    }
}
