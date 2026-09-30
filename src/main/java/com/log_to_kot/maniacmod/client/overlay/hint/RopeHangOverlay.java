package com.log_to_kot.maniacmod.client.overlay.hint;

import com.log_to_kot.maniacmod.client.rope.RopeHangClient;
import com.log_to_kot.maniacmod.entity.RopeTrapEntity;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Міні-гра розкачування: панель праворуч, де НАМАЛЬОВАНО гравця, що висить
 * на нитці й розгойдується. Кут — той самий, що синхронізує сервер
 * ({@code RopeTrapEntity#swing}), тож картинка не може розійтись з грою.
 * Смужка знизу — наскільки розкачано до розриву. Самі натискання A/D
 * читає {@link RopeHangClient}; тут лише малюнок.
 *
 * Гравець «намальований» прямокутниками (пікселі GUI), перевернутий догори
 * ногами: ноги прив'язані до нитки, голова внизу.
 */
@OnlyIn(Dist.CLIENT)
public final class RopeHangOverlay {

    private static final int PANEL_W = 130;
    private static final int PANEL_H = 176;

    private static final int COL_PANEL   = 0xAA101010;
    private static final int COL_BORDER  = 0xFF6B5A3C;
    private static final int COL_BEAM    = 0xFF6A6A6A;
    private static final int COL_THREAD  = 0xFFC9A66B;
    private static final int COL_SKIN    = 0xFFE0B48C;
    private static final int COL_SHIRT   = 0xFF3A6EA5;
    private static final int COL_PANTS   = 0xFF2B2B3A;

    private RopeHangOverlay() {}

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        RopeTrapEntity rope = RopeHangClient.localRope();
        if (rope == null || mc.screen != null || mc.options.hideGui) return;

        float theta = rope.swing(mc.getFrameTime());
        float limit = Math.max(0.1f, rope.breakAngle());
        float energy = Mth.clamp(Math.abs(theta) / limit, 0f, 1f);

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int x = sw - PANEL_W - 16;
        int y = sh / 2 - PANEL_H / 2;

        g.fill(x, y, x + PANEL_W, y + PANEL_H, COL_PANEL);
        g.renderOutline(x, y, PANEL_W, PANEL_H, COL_BORDER);

        // ── Маятник ─────────────────────────────────────────────────────
        int px = x + PANEL_W / 2;
        int py = y + 12;
        g.fill(px - 18, py - 5, px + 18, py, COL_BEAM);

        var pose = g.pose();
        pose.pushPose();
        pose.translate(px, py, 0f);
        // Екранний Y росте вниз, тому знак мінус: θ > 0 (вправо від погляду) = вправо на екрані.
        pose.mulPose(Axis.ZP.rotation(-theta));

        g.fill(-1, 0, 1, 46, COL_THREAD);                 // нитка
        g.fill(-4, 46, -1, 60, COL_PANTS);                // ноги (угорі — до нитки)
        g.fill(1, 46, 4, 60, COL_PANTS);
        g.fill(-5, 60, 5, 82, COL_SHIRT);                 // торс
        g.fill(-9, 60, -5, 76, COL_SHIRT);                // руки звисають донизу
        g.fill(5, 60, 9, 76, COL_SHIRT);
        g.fill(-9, 76, -5, 80, COL_SKIN);                 // кисті
        g.fill(5, 76, 9, 80, COL_SKIN);
        g.fill(-4, 82, 4, 91, COL_SKIN);                  // голова внизу
        g.fill(-2, 85, -1, 87, 0xFF202020);               // очі
        g.fill(1, 85, 2, 87, 0xFF202020);
        pose.popPose();

        // ── Смужка розкачування ─────────────────────────────────────────
        int bx = x + 10;
        int by = y + PANEL_H - 34;
        int bw = PANEL_W - 20;
        g.fill(bx, by, bx + bw, by + 6, 0xFF222222);
        int col = energy < 0.5f ? 0xFF6FBF4A : energy < 0.85f ? 0xFFE0B020 : 0xFFD04030;
        g.fill(bx, by, bx + Math.round(bw * energy), by + 6, col);
        g.renderOutline(bx - 1, by - 1, bw + 2, 8, 0xFF555555);

        // ── Підказки ────────────────────────────────────────────────────
        g.drawCenteredString(mc.font, Component.translatable("maniacmod.rope.hint_swing"),
            px, y + PANEL_H - 52, 0xFFFFFFFF);
        g.drawCenteredString(mc.font, Component.translatable("maniacmod.rope.hint_cut"),
            px, y + PANEL_H - 20, 0xFFB0B0B0);
    }
}
