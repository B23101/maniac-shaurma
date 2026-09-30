package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Іконка вікна гри (панель завдань / заголовок вікна) — той самий підхід,
 * що й у snipers_shaurma.
 *
 * ── Звідки береться файл ─────────────────────────────────────────────
 * {@code config/maniacmod/window_icon.png}. Якщо його ще немає, він
 * копіюється з ресурсів мода ({@code /config/maniacmod/window_icon.png}),
 * тож після першого запуску іконку можна підмінити власною без
 * перекомпіляції. Вже існуючий файл НЕ перезаписується.
 *
 * ── Відмовостійкість ─────────────────────────────────────────────────
 * Будь-яка проблема (нема файлу, не зображення) лише пишеться в лог —
 * гра лишається зі стандартною іконкою, запуск не падає.
 *
 * Викликати з головного потоку клієнта (див. {@code ClientSetup#onClientSetup},
 * {@code event.enqueueWork}): GLFW-виклики вікна з інших потоків небезпечні.
 */
@OnlyIn(Dist.CLIENT)
public final class CustomWindowIcon {

    private static final String ICON_FILENAME = "window_icon.png";

    private CustomWindowIcon() {}

    public static void trySetIcon() {
        Path iconPath = Path.of("config", ManiacMod.MOD_ID, ICON_FILENAME);
        if (!Files.exists(iconPath)) {
            try (InputStream in = CustomWindowIcon.class
                    .getResourceAsStream("/config/" + ManiacMod.MOD_ID + "/" + ICON_FILENAME)) {
                if (in != null) {
                    Files.createDirectories(iconPath.getParent());
                    Files.copy(in, iconPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                ManiacMod.LOGGER.error("[icon] не вдалося скопіювати іконку за замовчуванням", e);
            }
        }

        BufferedImage image;
        try {
            image = ImageIO.read(iconPath.toFile());
        } catch (IOException e) {
            ManiacMod.LOGGER.error("[icon] не вдалося прочитати {}", iconPath, e);
            return;
        }
        if (image == null) {
            ManiacMod.LOGGER.warn("[icon] непідтримуваний формат зображення: {}", iconPath);
            return;
        }

        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) return;

        int[] pixels = new int[w * h];
        image.getRGB(0, 0, w, h, pixels, 0, w);

        ByteBuffer buffer = ByteBuffer.allocateDirect(w * h * 4);
        for (int pixel : pixels) {
            buffer.put((byte) ((pixel >> 16) & 0xFF)); // R
            buffer.put((byte) ((pixel >> 8) & 0xFF));  // G
            buffer.put((byte) (pixel & 0xFF));         // B
            buffer.put((byte) ((pixel >> 24) & 0xFF)); // A
        }
        buffer.flip();

        long window = Minecraft.getInstance().getWindow().getWindow();
        try (GLFWImage.Buffer imageBuffer = GLFWImage.malloc(1)) {
            imageBuffer.width(w);
            imageBuffer.height(h);
            imageBuffer.pixels(buffer);
            GLFW.glfwSetWindowIcon(window, imageBuffer);
        }

        ManiacMod.LOGGER.info("[icon] іконку вікна встановлено з {} ({}x{})", iconPath, w, h);
    }
}
