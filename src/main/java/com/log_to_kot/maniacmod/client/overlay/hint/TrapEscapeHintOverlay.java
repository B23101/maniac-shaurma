package com.log_to_kot.maniacmod.client.overlay.hint;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Підказка "Натисни F, щоб вибратись" — низом по центру екрана, ЛИШЕ
 * ТЕКСТ (той самий стиль панелі, що {@code GeneratorProgressOverlay}:
 * та сама {@link GeneratorFuelUiTheme}-панель, той самий нижній
 * відступ від низу екрана), але без прогрес-бару — на момент показу
 * підказки жертва ще НІЧОГО не почала, самої спроби (повзунка) ще
 * немає, тож і бар показувати нема чого.
 *
 * ── Коли видима ──────────────────────────────────────────────────────
 * Рівно поки сервер тримає для гравця стан "чекає F". Сервер сам
 * вмикає й вимикає підказку пакетом {@code TrapEscapePromptPacket}
 * ({@link ClientMatchState#isTrapEscapePrompt()}): {@code isTrapped()}
 * для цього не годиться, бо лишається true і після втраченої спроби.
 * Підказка й екран міні-гри ніколи не видно одночасно: при натисканні F
 * сервер гасить підказку й відкриває екран, а {@code mc.screen != null}
 * додатково ізолює рендер.
 */
@OnlyIn(Dist.CLIENT)
public final class TrapEscapeHintOverlay {

    private static final int PANEL_WIDTH = 250;
    private static final int PADDING = 10;
    private static final int TEXT_ROW_HEIGHT = 10;
    private static final int PANEL_HEIGHT = PADDING * 2 + TEXT_ROW_HEIGHT;
    private static final int BOTTOM_MARGIN = 20;

    private TrapEscapeHintOverlay() {}

    // Плавна поява — той самий підхід, що GeneratorHintOverlay.
    private static final long[] lastFrameNanos = new long[1];
    private static float appear = 0f;

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();

        boolean hungOnRope = com.log_to_kot.maniacmod.client.rope.RopeHangClient.isLocalHung();
        boolean visible = (ClientMatchState.isTrapEscapePrompt() || hungOnRope) && mc.screen == null;

        if (!visible) {
            appear = 0f;
            lastFrameNanos[0] = 0L;
            return;
        }

        float dt = UiAnim.frameDt(lastFrameNanos);
        appear = UiAnim.approach(appear, 1f, 11f, dt);
        float ease = UiAnim.easeOutCubic(UiAnim.clamp01(appear));

        String text = Component.translatable(com.log_to_kot.maniacmod.client.rope.RopeHangClient.isLocalHung()
            ? "maniacmod.rope.escape_hint" : "maniacmod.trap.escape_press_f_hint").getString();
        drawHint(graphics, mc.font, text, ease);
    }

    private static void drawHint(GuiGraphics graphics, Font font, String text, float ease) {
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // Ширина від реального тексту, з мінімумом — той самий підхід,
        // що GeneratorHintOverlay, щоб довший переклад не вилазив за межі панелі.
        int textWidth = font.width(text);
        int panelWidth = Math.max(PANEL_WIDTH, textWidth + PADDING * 2);

        int panelX = screenW / 2 - panelWidth / 2;
        int panelY = screenH - BOTTOM_MARGIN - PANEL_HEIGHT;

        // Плавна поява знизу — той самий підйом+масштаб, що в GeneratorHintOverlay.
        graphics.pose().pushPose();
        float cx = panelX + panelWidth / 2f;
        float cy = panelY + PANEL_HEIGHT / 2f;
        graphics.pose().translate(cx, cy + (1f - ease) * 10f, 0f);
        float scale = 0.94f + 0.06f * ease;
        graphics.pose().scale(scale, scale, 1f);
        graphics.pose().translate(-cx, -cy, 0f);

        GeneratorFuelUiTheme.drawPanel(graphics, panelX, panelY, panelWidth, PANEL_HEIGHT);

        int textX = panelX + panelWidth / 2 - font.width(text) / 2;
        int textY = panelY + PADDING;
        graphics.drawString(font, text, textX, textY, GeneratorFuelUiTheme.TEXT, false);

        graphics.pose().popPose();
    }
}
