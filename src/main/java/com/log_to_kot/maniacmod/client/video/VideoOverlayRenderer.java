package com.log_to_kot.maniacmod.client.video;

import com.log_to_kot.maniacmod.ManiacMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Об'єднаний overlay рендерер.
 * Пріоритет: найвищий — перекриває весь стандартний HUD.
 *
 * Логіка:
 *  1. Якщо FFmpeg ще завантажується → показати FfmpegDownloadScreen
 *  2. Якщо відео грає → показати повноекранний кадр + прогрес-бар
 *  3. Інакше → нічого не робити (звичайний HUD)
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT)
public class VideoOverlayRenderer {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderGuiPre(RenderGuiOverlayEvent.Pre event) {

        // ── 1. FFmpeg завантажується / встановлюється ──────────────────────
        FfmpegInstaller.State ffState = FfmpegInstaller.getState();
        if (ffState == FfmpegInstaller.State.DOWNLOADING ||
            ffState == FfmpegInstaller.State.EXTRACTING  ||
            ffState == FfmpegInstaller.State.CHECKING) {
            event.setCanceled(true);
            FfmpegDownloadScreen.render(event.getGuiGraphics());
            return;
        }

        // ── 2. Відео грає ──────────────────────────────────────────────────
        if (VideoPlayer.isPlaying()) {
            event.setCanceled(true);
            PoseStack pose = event.getGuiGraphics().pose();
            VideoPlayer.renderFrame(pose);
            renderProgressBar(pose);
        }
    }

    // ── Тонка смужка прогресу внизу ──────────────────────────────────────────

    private static void renderProgressBar(PoseStack pose) {
        float progress = VideoPlayer.getProgress();
        if (progress <= 0f) return;

        Minecraft mc = Minecraft.getInstance();
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();
        int barW = (int)(W * progress);
        int barY = H - 3;

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // Фон
        buf.vertex(pose.last().pose(),  0, H,   0).color(0,   0,  0, 120).endVertex();
        buf.vertex(pose.last().pose(),  W, H,   0).color(0,   0,  0, 120).endVertex();
        buf.vertex(pose.last().pose(),  W, barY,0).color(0,   0,  0, 120).endVertex();
        buf.vertex(pose.last().pose(),  0, barY,0).color(0,   0,  0, 120).endVertex();
        // Заповнення
        buf.vertex(pose.last().pose(),  0, H,   0).color(180,20, 20, 200).endVertex();
        buf.vertex(pose.last().pose(), barW,H,  0).color(180,20, 20, 200).endVertex();
        buf.vertex(pose.last().pose(), barW,barY,0).color(180,20,20,200).endVertex();
        buf.vertex(pose.last().pose(),  0, barY,0).color(180,20, 20, 200).endVertex();
        Tesselator.getInstance().end();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}
