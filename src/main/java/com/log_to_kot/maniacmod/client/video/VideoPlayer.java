package com.log_to_kot.maniacmod.client.video;

import com.log_to_kot.maniacmod.ManiacMod;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ══════════════════════════════════════════════════════════════════
 *  ВІДЕОПРОГРАВАЧ — АВТОМАТИЧНИЙ FFMPEG
 * ══════════════════════════════════════════════════════════════════
 *
 *  Відео зберігається в: .minecraft/maniacmod/video/<назва>.mp4
 *
 *  При першому запуску мода:
 *    1. FfmpegInstaller автоматично завантажує FFmpeg (~45 МБ)
 *       з https://github.com/BtbN/FFmpeg-Builds
 *    2. Прогрес показується на екрані через FfmpegDownloadScreen
 *
 *  Як додати відео:
 *    Просто покладіть intro.mp4 у папку:
 *      .minecraft/maniacmod/video/intro.mp4
 *
 *  При відтворенні:
 *    1. FFmpeg витягує кадри у тимчасову папку (фоновий потік)
 *    2. Рендер-потік завантажує кадри через NativeImage → DynamicTexture
 *    3. Повноекранний quad відображається через VideoOverlayRenderer
 *    4. Після завершення — callback onFinish
 */
public class VideoPlayer {

    // ── Стан ─────────────────────────────────────────────────────────────────

    private static volatile boolean  playing       = false;
    private static volatile int      currentFrame  = 0;
    private static volatile int      totalFrames   = 0;
    private static volatile long     frameDelayMs  = 42;   // 24 FPS
    private static volatile long     lastFrameTime = 0;
    private static          Runnable onFinish      = null;

    /** Директорія з витягнутими JPEG кадрами */
    private static volatile Path framesDir = null;

    /** Чи вже закінчив FFmpeg витягувати кадри */
    private static final AtomicBoolean framesReady = new AtomicBoolean(false);

    /** OpenGL текстура */
    private static DynamicTexture   frameTexture = null;
    private static ResourceLocation frameTexLoc  = null;

    // ── Папки ─────────────────────────────────────────────────────────────────

    private static Path getVideoDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("maniacmod").resolve("video");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Відтворити відео.
     *
     * @param videoName  "intro" → .minecraft/maniacmod/video/intro.mp4
     * @param fps        FPS відтворення (24 рекомендовано)
     * @param callback   Виклик по завершенні
     */
    public static void play(String videoName, int fps, Runnable callback) {
        if (playing) stop();

        // Прибрати розширення якщо передали "intro.mp4"
        String baseName = videoName.endsWith(".mp4")
            ? videoName.substring(0, videoName.length() - 4)
            : videoName;

        frameDelayMs  = 1000L / Math.max(1, fps);
        onFinish      = callback;
        currentFrame  = 0;
        totalFrames   = 0;
        playing       = false;
        framesReady.set(false);

        // Спочатку переконуємося що FFmpeg є
        FfmpegInstaller.ensureAvailable(() -> {
            // Запускаємо витягування кадрів у фоні
            new Thread(() -> extractAndPlay(baseName, fps), "VideoPlayer-Extract").start();
        });
    }

    public static void stop() {
        playing = false;
        currentFrame = 0;
        framesReady.set(false);
        cleanupTexture();
        cleanupFrames();
    }

    public static boolean isPlaying()    { return playing; }
    public static int  getCurrentFrame() { return currentFrame; }
    public static int  getTotalFrames()  { return totalFrames; }

    public static float getProgress() {
        if (totalFrames <= 0) return 0f;
        return (float) currentFrame / totalFrames;
    }

    // ── Витягування кадрів через FFmpeg ──────────────────────────────────────

    private static void extractAndPlay(String baseName, int fps) {
        Path videoFile = getVideoDir().resolve(baseName + ".mp4");

        if (!Files.exists(videoFile)) {
            ManiacMod.LOGGER.error("[VideoPlayer] Файл відео не знайдено: {}", videoFile);
            ManiacMod.LOGGER.error("[VideoPlayer] Помісти відео сюди: {}", videoFile);
            runCallback();
            return;
        }

        String ffmpeg = FfmpegInstaller.getFfmpegPath();
        if (ffmpeg == null) {
            ManiacMod.LOGGER.error("[VideoPlayer] FFmpeg не доступний");
            runCallback();
            return;
        }

        try {
            // Тимчасова папка для кадрів
            framesDir = Files.createTempDirectory("maniacmod_video_");
            ManiacMod.LOGGER.info("[VideoPlayer] Витягування кадрів у {}", framesDir);

            // Запустити FFmpeg
            ProcessBuilder pb = new ProcessBuilder(
                ffmpeg,
                "-i",   videoFile.toAbsolutePath().toString(),
                "-vf",  "fps=" + fps + ",scale=iw:ih",
                "-q:v", "3",           // якість JPEG (1=найкраще, 31=найгірше)
                "-f",   "image2",
                framesDir.resolve("f%06d.jpg").toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            // Читаємо лог FFmpeg (важливо — інакше процес зависне на Windows)
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    ManiacMod.LOGGER.debug("[FFmpeg] {}", line);
                }
            }

            int exitCode = proc.waitFor();
            ManiacMod.LOGGER.info("[VideoPlayer] FFmpeg завершив з кодом {}", exitCode);

            // Підрахувати кадри
            try (var stream = Files.list(framesDir)) {
                totalFrames = (int) stream
                    .filter(p -> p.toString().endsWith(".jpg"))
                    .count();
            }

            if (totalFrames == 0) {
                ManiacMod.LOGGER.error("[VideoPlayer] Жодного кадру не витягнуто");
                runCallback();
                return;
            }

            ManiacMod.LOGGER.info("[VideoPlayer] {} кадрів готові", totalFrames);
            framesReady.set(true);

            // Запустити рендеринг на головному потоці
            Minecraft.getInstance().execute(() -> {
                if (frameTexture == null) {
                    frameTexture = new DynamicTexture(1, 1, false);
                    frameTexLoc  = Minecraft.getInstance()
                        .getTextureManager()
                        .register("maniacmod_video", frameTexture);
                }
                playing       = true;
                currentFrame  = 1;
                lastFrameTime = System.currentTimeMillis();
                ManiacMod.LOGGER.info("[VideoPlayer] Відтворення розпочато");
            });

        } catch (Exception e) {
            ManiacMod.LOGGER.error("[VideoPlayer] Помилка: {}", e.getMessage());
            runCallback();
        }
    }

    // ── Рендеринг (викликається кожен render-tick) ────────────────────────────

    public static void renderFrame(PoseStack pose) {
        if (!playing || framesDir == null || !framesReady.get()) return;

        long now = System.currentTimeMillis();
        if (now - lastFrameTime >= frameDelayMs) {
            currentFrame++;
            lastFrameTime = now;
        }

        if (currentFrame > totalFrames) {
            playing = false;
            cleanupTexture();
            cleanupFrames();
            runCallback();
            return;
        }

        // Завантажити поточний кадр
        Path framePath = framesDir.resolve(String.format("f%06d.jpg", currentFrame));
        if (!Files.exists(framePath)) return;

        try (InputStream is = new BufferedInputStream(Files.newInputStream(framePath), 65536)) {
            NativeImage img = NativeImage.read(is);
            frameTexture.setPixels(img);
            frameTexture.upload();
        } catch (IOException e) {
            return; // пошкоджений кадр — пропустити
        }

        // Намалювати повноекранний quad
        Minecraft mc = Minecraft.getInstance();
        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, frameTexLoc);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableDepthTest();

        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buf.vertex(pose.last().pose(),  0, H, 0).uv(0f, 1f).endVertex();
        buf.vertex(pose.last().pose(),  W, H, 0).uv(1f, 1f).endVertex();
        buf.vertex(pose.last().pose(),  W, 0, 0).uv(1f, 0f).endVertex();
        buf.vertex(pose.last().pose(),  0, 0, 0).uv(0f, 0f).endVertex();
        Tesselator.getInstance().end();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void runCallback() {
        Minecraft.getInstance().execute(() -> {
            if (onFinish != null) { onFinish.run(); onFinish = null; }
        });
    }

    private static void cleanupTexture() {
        if (frameTexture != null) {
            frameTexture.close();
            frameTexture = null;
            frameTexLoc  = null;
        }
    }

    private static void cleanupFrames() {
        if (framesDir != null) {
            Path dir = framesDir;
            framesDir = null;
            new Thread(() -> {
                try {
                    Files.walk(dir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                } catch (IOException ignored) {}
            }, "VideoPlayer-Cleanup").start();
        }
    }
}
