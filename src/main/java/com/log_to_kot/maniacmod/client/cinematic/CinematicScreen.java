package com.log_to_kot.maniacmod.client.cinematic;

import com.log_to_kot.maniacmod.ManiacMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * CLIENT-SIDE full-screen cinematic renderer.
 *
 * How it works:
 *  1. Server sends a CinematicStartPacket with a frame list + timing
 *  2. Client stores the frame sequence
 *  3. Every render tick (RenderGuiOverlayEvent.Pre) we draw the current frame
 *     as a full-screen quad on top of EVERYTHING — world, HUD, chat
 *  4. When all frames are done → renderer deactivates itself
 *
 * Frames are PNG files stored in:
 *   assets/maniacmod/textures/cinematic/frame_0000.png
 *   assets/maniacmod/textures/cinematic/frame_0001.png
 *   ...etc
 *
 * Frame rate: configurable, default 10 FPS (100ms per frame)
 * The sound (game_start.ogg) plays independently via SoundHelper.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT)
public class CinematicScreen {

    // ── State ─────────────────────────────────────────────────────────────────

    private static boolean active           = false;
    private static int     totalFrames      = 0;
    private static int     currentFrame     = 0;
    private static long    frameDelayMs     = 100;  // 10 FPS default
    private static long    lastFrameTimeMs  = 0;
    private static Runnable onFinishCallback = null;

    /** Prefix for frame textures: assets/maniacmod/textures/cinematic/frame_XXXX.png */
    private static final String FRAME_PATH_PREFIX = "textures/cinematic/frame_";

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start playing a cinematic sequence.
     *
     * @param frameCount   total number of PNG frames (frame_0000 ... frame_NNNN)
     * @param fps          playback speed (10 = smooth for simple animations)
     * @param onFinish     called on client thread when last frame is shown
     */
    public static void start(int frameCount, int fps, Runnable onFinish) {
        totalFrames      = frameCount;
        currentFrame     = 0;
        frameDelayMs     = 1000L / Math.max(1, fps);
        lastFrameTimeMs  = System.currentTimeMillis();
        onFinishCallback = onFinish;
        active           = true;
        ManiacMod.LOGGER.info("[Cinematic] Starting {} frames @ {} FPS", frameCount, fps);
    }

    /** Stop immediately (e.g. player disconnects). */
    public static void stop() {
        active       = false;
        currentFrame = 0;
    }

    public static boolean isActive() { return active; }

    // ── Render hook ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderGuiPre(RenderGuiOverlayEvent.Pre event) {
        if (!active) return;

        Minecraft mc = Minecraft.getInstance();
        int screenW  = mc.getWindow().getGuiScaledWidth();
        int screenH  = mc.getWindow().getGuiScaledHeight();

        // Advance frame based on elapsed time
        long now = System.currentTimeMillis();
        if (now - lastFrameTimeMs >= frameDelayMs) {
            currentFrame++;
            lastFrameTimeMs = now;

            if (currentFrame >= totalFrames) {
                active = false;
                currentFrame = totalFrames - 1; // hold last frame briefly
                if (onFinishCallback != null) {
                    onFinishCallback.run();
                    onFinishCallback = null;
                }
            }
        }

        // Build texture path: maniacmod:textures/cinematic/frame_0000.png
        String frameName = String.format("%s%04d", FRAME_PATH_PREFIX, currentFrame);
        ResourceLocation texture = new ResourceLocation(ManiacMod.MOD_ID, frameName);

        // Draw full-screen quad
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buf.vertex(0,       screenH, 0).uv(0, 1).endVertex();
        buf.vertex(screenW, screenH, 0).uv(1, 1).endVertex();
        buf.vertex(screenW, 0,       0).uv(1, 0).endVertex();
        buf.vertex(0,       0,       0).uv(0, 0).endVertex();
        Tesselator.getInstance().end();

        RenderSystem.disableBlend();
    }
}
