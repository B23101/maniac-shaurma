package com.log_to_kot.maniacmod.client.overlay;

import com.log_to_kot.maniacmod.ManiacMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.language.I18n;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Темний повноекранний overlay який показується всім гравцям (крім маньяка)
 * поки маньяк вибирає персонажа в ManiacSelectScreen.
 *
 * Показує:
 *  - Темний напів-прозорий фон
 *  - "Маньяк вибирає персонажа..."
 *  - Таймер що зменшується: "60"
 *
 * Прибирається коли маньяк вибрав або час вийшов (SelectWaitingPacket active=false).
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT)
public class SelectWaitingOverlay {

    private static volatile boolean active      = false;
    private static volatile int     secondsLeft = 0;

    /** Викликається з SelectWaitingPacket */
    public static void setState(boolean isActive, int secs) {
        active      = isActive;
        secondsLeft = secs;
    }

    public static boolean isActive() { return active; }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderGui(RenderGuiOverlayEvent.Pre event) {
        if (!active) return;

        // Скасувати звичайний HUD
        event.setCanceled(true);

        Minecraft mc  = Minecraft.getInstance();
        int W         = mc.getWindow().getGuiScaledWidth();
        int H         = mc.getWindow().getGuiScaledHeight();
        var font      = mc.font;
        var guiGraphics = event.getGuiGraphics();
        var ps        = guiGraphics.pose();

        // ── Темний фон ───────────────────────────────────────────────────────
        drawRect(ps, 0, 0, W, H, 0xE5000000);

        // ── Червона лінія зверху/знизу ───────────────────────────────────────
        drawRect(ps, 0, 0,   W, 3,   0xFF8B0000);
        drawRect(ps, 0, H-3, W, H,   0xFF8B0000);

        // ── Основний текст ───────────────────────────────────────────────────
        String line1 = I18n.get("maniacmod.select.waiting_overlay.title");
        String line2 = I18n.get("maniacmod.select.waiting_overlay.subtitle");

        int cy = H / 2 - 20;
        guiGraphics.drawString(font, line1,
            (W - font.width(line1)) / 2, cy, 0xFFCC2222, true);

        cy += 16;
        guiGraphics.drawString(font, line2,
            (W - font.width(line2)) / 2, cy, 0xFF888888, true);

        // ── Таймер ───────────────────────────────────────────────────────────
        cy += 28;
        String timerLabel = I18n.get("maniacmod.select.waiting_overlay.timer");
        String timerValue = String.valueOf(secondsLeft);

        // Колір таймера: червоніє ближче до кінця
        int timerColor = secondsLeft <= 10 ? 0xFFFF3333
                       : secondsLeft <= 30 ? 0xFFFF9900
                       : 0xFFFFFFFF;

        // Маленький підпис
        guiGraphics.drawString(font, timerLabel,
            (W - font.width(timerLabel)) / 2, cy, 0xFF666666, true);

        // Великий номер (малювати вдвічі більшим через scale)
        cy += 12;
        ps.pushPose();
        ps.translate(W / 2f, cy, 0);
        ps.scale(2.5f, 2.5f, 1f);
        guiGraphics.drawString(font, timerValue,
            -font.width(timerValue) / 2, 0, timerColor, true);
        ps.popPose();
    }

    // ── GL helper ─────────────────────────────────────────────────────────────

    private static void drawRect(PoseStack ps, int x1, int y1, int x2, int y2, int color) {
        float a = ((color >> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >>  8) & 0xFF) / 255f;
        float b = ( color        & 0xFF) / 255f;

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buf.vertex(ps.last().pose(), x1, y2, 0).color(r,g,b,a).endVertex();
        buf.vertex(ps.last().pose(), x2, y2, 0).color(r,g,b,a).endVertex();
        buf.vertex(ps.last().pose(), x2, y1, 0).color(r,g,b,a).endVertex();
        buf.vertex(ps.last().pose(), x1, y1, 0).color(r,g,b,a).endVertex();
        Tesselator.getInstance().end();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}
