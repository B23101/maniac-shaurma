package com.log_to_kot.maniacmod.client.video;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Рендерить темний overlay з прогрес-баром завантаження FFmpeg.
 * Викликається з VideoOverlayRenderer.
 */
@OnlyIn(Dist.CLIENT)
public class FfmpegDownloadScreen {

    /** Викликається з VideoOverlayRenderer */
    public static void render(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        PoseStack ps = guiGraphics.pose();

        // ── Темний напів-прозорий фон ─────────────────────────────────────
        drawRect(ps, 0, 0, W, H, 0xCC000000);

        // ── Центральна панель ─────────────────────────────────────────────
        int panelW = Math.min(420, W - 40);
        int panelH = 96;
        int panelX = (W - panelW) / 2;
        int panelY = (H - panelH) / 2;

        drawRect(ps, panelX,     panelY,          panelX + panelW, panelY + panelH, 0xEE111111);
        // Рамка — червона
        drawRect(ps, panelX,     panelY,          panelX + panelW, panelY + 2,     0xFF8B0000);
        drawRect(ps, panelX,     panelY+panelH-2, panelX + panelW, panelY+panelH,  0xFF8B0000);
        drawRect(ps, panelX,     panelY,          panelX + 2,      panelY+panelH,  0xFF8B0000);
        drawRect(ps, panelX+panelW-2, panelY,     panelX+panelW,   panelY+panelH,  0xFF8B0000);

        var font = mc.font;

        // ── Заголовок ──────────────────────────────────────────────────────
        guiGraphics.drawString(font, "§c⚙ ManiacMod — Встановлення FFmpeg",
            panelX + 10, panelY + 9, 0xFFFFFFFF, true);

        // ── Статус ─────────────────────────────────────────────────────────
        String status = FfmpegInstaller.getStatus();
        if (status.length() > 60) status = status.substring(0, 57) + "...";
        guiGraphics.drawString(font, "§7" + status, panelX + 10, panelY + 24, 0xFFBBBBBB, true);

        // ── Прогрес-бар ────────────────────────────────────────────────────
        int barX = panelX + 10;
        int barY = panelY + 44;
        int barW = panelW - 20;
        int barH = 16;

        drawRect(ps, barX, barY, barX + barW, barY + barH, 0xFF222222);

        int filled = (int)(barW * FfmpegInstaller.getProgress() / 100f);
        if (filled > 0) {
            // Градієнт: темно-червоний знизу, яскравіший зверху
            drawRect(ps, barX, barY,          barX + filled, barY + barH,     0xFF8B0000);
            drawRect(ps, barX, barY,          barX + filled, barY + barH / 2, 0x66C41230);
        }

        // Рамка бару
        drawRect(ps, barX,        barY,      barX+barW, barY+1,    0xFF444444);
        drawRect(ps, barX,        barY+barH-1, barX+barW, barY+barH, 0xFF444444);
        drawRect(ps, barX,        barY,      barX+1,    barY+barH, 0xFF444444);
        drawRect(ps, barX+barW-1, barY,      barX+barW, barY+barH, 0xFF444444);

        // Відсоток
        String pct = FfmpegInstaller.getProgress() + "%";
        guiGraphics.drawString(font, pct, barX + (barW - font.width(pct)) / 2, barY + 4, 0xFFFFFFFF, true);

        // ── Підказка ──────────────────────────────────────────────────────
        FfmpegInstaller.State s = FfmpegInstaller.getState();
        String hint = s == FfmpegInstaller.State.FAILED
            ? "§cПомилка: " + shorten(FfmpegInstaller.getError(), 55)
            : "§8Автоматичне завантаження — зачекайте (~45 МБ)";
        guiGraphics.drawString(font, hint, panelX + 10, panelY + 74, 0xFF888888, true);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String shorten(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max - 3) + "..." : (s != null ? s : "");
    }

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
