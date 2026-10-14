package com.log_to_kot.maniacmod.client.screen.mainmenu;

import com.log_to_kot.maniacmod.ManiacMod;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Фон головного меню: картинка, що повільно їде туди-сюди (як у снайперів).
 *
 * ── Рух ──────────────────────────────────────────────────────────────
 * Картинка масштабується так, щоб покривати екран по висоті й бути щонайменше
 * на {@link #MIN_OVERSCAN} ширшою за нього — тож є куди їхати навіть якщо
 * підкладено не панораму. Далі зсув по X ходить від 0 до {@code -(ширина - екран)}
 * і назад за {@link #PERIOD_NS} (трикутна хвиля + easeInOutQuad: на краях
 * картинка плавно розвертається, а не відскакує). Старт — з лівого краю, тобто
 * спершу картинка їде ПРАВО→ВЛІВО.
 *
 * ── Плавність ────────────────────────────────────────────────────────
 * Зсув іде через {@code PoseStack.translate(float…)}, а не через цілий піксель
 * координати квада: біля розворотів швидкість майже нульова, і округлення до
 * пікселя «застрягало» б картинку на кілька кадрів поспіль.
 *
 * ── Звідки картинка ──────────────────────────────────────────────────
 * Ресурс {@code assets/maniacmod/textures/gui/main_menu/background.png}
 * (його можна підмінити ресурспаком). Читається один раз у
 * {@link DynamicTexture}; якщо файлу нема чи він битий — {@link #render}
 * повертає {@code false}, а екран малює темний градієнт замість картинки.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacMainMenuBackground {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation SOURCE =
        new ResourceLocation(ManiacMod.MOD_ID, "textures/gui/main_menu/background.png");
    private static final ResourceLocation TEX_ID =
        new ResourceLocation(ManiacMod.MOD_ID, "dynamic/main_menu_background");

    /** Повний цикл «туди-сюди», нс. */
    private static final long PERIOD_NS = 40_000_000_000L;
    /** Мінімальна ширина картинки відносно екрана — запас для руху. */
    private static final float MIN_OVERSCAN = 1.25f;

    private static DynamicTexture texture;
    private static int imgW;
    private static int imgH;
    private static boolean loadAttempted;
    /** Спільний годинник: фон не «стрибає», коли гравець ходить між меню, списками й екраном модів. */
    private static long sharedStartNs;

    private ManiacMainMenuBackground() {}

    private static void ensureLoaded() {
        if (loadAttempted) return;
        loadAttempted = true;

        Minecraft mc = Minecraft.getInstance();
        Optional<Resource> res = mc.getResourceManager().getResource(SOURCE);
        if (res.isEmpty()) {
            LOGGER.warn("[ManiacMod] Фон головного меню не знайдено: {}", SOURCE);
            return;
        }
        try (InputStream in = res.get().open()) {
            NativeImage img = NativeImage.read(in);
            imgW = img.getWidth();
            imgH = img.getHeight();
            DynamicTexture tex = new DynamicTexture(img);
            // Лінійне згладжування: картинка завжди стискається до висоти екрана,
            // з найближчими сусідами вийшла б «драбина» на гілках.
            tex.setFilter(true, false);
            mc.getTextureManager().register(TEX_ID, tex);
            texture = tex;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[ManiacMod] Не вдалося завантажити фон головного меню {}", SOURCE, e);
        }
    }

    /**
     * Малює картинку на весь екран.
     *
     * @param elapsedNs наносекунд від відкриття екрана (фаза руху)
     * @return {@code false}, якщо картинки нема — тоді малюй запасний фон сам
     */
    public static boolean render(GuiGraphics g, int w, int h, long elapsedNs) {
        ensureLoaded();
        if (texture == null) return false;

        float scale = Math.max(h / (float) imgH, w * MIN_OVERSCAN / imgW);
        float drawW = imgW * scale;
        float drawH = imgH * scale;

        double t = (elapsedNs % PERIOD_NS) / (double) PERIOD_NS;
        float tri = (float) (t < 0.5 ? t * 2.0 : 2.0 - t * 2.0);
        float phase = easeInOutQuad(tri);

        float offX = -Math.max(0f, drawW - w) * phase;
        float offY = -Math.max(0f, drawH - h) / 2f;

        g.pose().pushPose();
        g.pose().translate(offX, offY, 0f);
        g.blit(TEX_ID, 0, 0, Math.round(drawW), Math.round(drawH), 0, 0, imgW, imgH, imgW, imgH);
        g.pose().popPose();
        return true;
    }

    /** Наносекунди від першого показу фону — спільна фаза руху для всіх екранів. */
    public static long sharedElapsedNs() {
        if (sharedStartNs == 0L) sharedStartNs = System.nanoTime();
        return System.nanoTime() - sharedStartNs;
    }

    /** Те саме, що {@link #render}, але фаза руху спільна для всіх екранів, що малюють цей фон. */
    public static boolean renderShared(GuiGraphics g, int w, int h) {
        return render(g, w, h, sharedElapsedNs());
    }

    private static float easeInOutQuad(float t) {
        return t < 0.5f ? 2f * t * t : 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f;
    }
}
