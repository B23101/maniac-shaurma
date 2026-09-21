package com.log_to_kot.maniacmod.client.overlay.actionprogress;

import com.log_to_kot.maniacmod.client.ClientInputHandler;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.net.s2c.matchstate.DownedSurvivorsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Екран підняття непритомного — дві сторони однієї дії.
 *
 * ── Лежачий ──────────────────────────────────────────────────────────
 * Панель «Ти без свідомості»: шкала часу до смерті й, коли його піднімають,
 * шкала підняття. Час береться зі списку {@code DownedSurvivorsPacket}
 * (клієнт відлічує сам між пакетами), тож шкала тече плавно.
 *
 * ── Рятівник ─────────────────────────────────────────────────────────
 * Поки утримує ПКМ на лежачому — шкала «Підняття союзника». Поки ще не
 * тримає, але дивиться на нього, — підказка «Утримуй ПКМ» (як біля
 * генератора), щоб гравець узагалі знав, що це можна.
 *
 * Шкала підняття зникає сама, коли пакети прогресу припиняються (див.
 * {@code RescueProgressPacket}): окремого «сховати» немає.
 */
@OnlyIn(Dist.CLIENT)
public final class RescueOverlay {

    private static final int PANEL_W = 250;
    private static final int RESCUER_PANEL_W = 190;
    private static final int BAR_H = 10;
    private static final int MARGIN = 10;
    private static final int OFFSET_FROM_CENTER = 42;

    private RescueOverlay() {}

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (ClientMatchState.isDowned(mc.player.getUUID())) {
            renderVictim(graphics, mc);
        } else if (ClientMatchState.isSurvivor()) {
            if (ClientMatchState.rescueActive() && !ClientMatchState.rescueAsVictim()) {
                renderRescuer(graphics, mc);
            } else if (ClientInputHandler.isLookingAtDownedSurvivor()
                    && !ClientMatchState.survivorState().isCrawlOnly()) {
                renderHint(graphics, mc);
            }
        }
    }

    // ── Лежачий ──────────────────────────────────────────────────────────

    private static void renderVictim(GuiGraphics graphics, Minecraft mc) {
        DownedSurvivorsPacket.Entry self = null;
        for (DownedSurvivorsPacket.Entry entry : ClientMatchState.downedEntries()) {
            if (entry.id().equals(mc.player.getUUID())) {
                self = entry;
                break;
            }
        }
        if (self == null) return;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int panelH = 68;
        int x = screenW / 2 - PANEL_W / 2;
        int y = screenH / 2 + OFFSET_FROM_CENTER;

        graphics.fill(x, y, x + PANEL_W, y + panelH, ManiacUiTheme.PANEL_BG);
        ManiacUiTheme.border1px(graphics, x, y, PANEL_W, panelH, ManiacUiTheme.BAR_FILL_DANGER);

        int titleY = y + MARGIN - 4;
        graphics.drawCenteredString(mc.font, Component.translatable("maniacmod.hud.downed.title"),
            screenW / 2, titleY, ManiacUiTheme.BAR_FILL_DANGER);
        ManiacUiTheme.divider(graphics, x + MARGIN, titleY + mc.font.lineHeight + 4, PANEL_W - MARGIN * 2);

        int barX = x + MARGIN;
        int barW = PANEL_W - MARGIN * 2;
        int bar1Y = titleY + mc.font.lineHeight + 12;

        // Шкала часу до смерті: повна на початку, порожніє.
        long leftMs = ClientMatchState.downedMillisLeft(self);
        boolean urgent = leftMs < 10_000;
        ManiacUiTheme.drawProgressBar(graphics, barX, bar1Y, barW, BAR_H,
            ClientMatchState.downedFraction(self),
            urgent ? ManiacUiTheme.BAR_FILL_DANGER : ManiacUiTheme.BAR_FILL_WARN);
        long seconds = (leftMs + 999) / 1000;
        String time = (seconds / 60) + ":" + String.format("%02d", seconds % 60);
        graphics.drawCenteredString(mc.font,
            Component.translatable("maniacmod.hud.downed.time", time),
            screenW / 2, bar1Y + (BAR_H - mc.font.lineHeight) / 2 + 1, ManiacUiTheme.TEXT_TITLE);

        int row2Y = bar1Y + BAR_H + 8;
        if (ClientMatchState.rescueActive() && ClientMatchState.rescueAsVictim()) {
            ManiacUiTheme.drawProgressBar(graphics, barX, row2Y, barW, BAR_H,
                ClientMatchState.rescueFraction(), ManiacUiTheme.BAR_FILL_NEUTRAL);
            graphics.drawCenteredString(mc.font,
                Component.translatable("maniacmod.hud.downed.being_rescued"),
                screenW / 2, row2Y + (BAR_H - mc.font.lineHeight) / 2 + 1, ManiacUiTheme.TEXT_TITLE);
        } else {
            graphics.drawCenteredString(mc.font, Component.translatable("maniacmod.hud.downed.hint"),
                screenW / 2, row2Y + 1, ManiacUiTheme.TEXT_DIM);
        }
    }

    // ── Рятівник ─────────────────────────────────────────────────────────

    private static void renderRescuer(GuiGraphics graphics, Minecraft mc) {
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int panelH = 46;
        int x = screenW / 2 - RESCUER_PANEL_W / 2;
        int y = screenH / 2 + OFFSET_FROM_CENTER;

        graphics.fill(x, y, x + RESCUER_PANEL_W, y + panelH, ManiacUiTheme.PANEL_BG);
        ManiacUiTheme.border1px(graphics, x, y, RESCUER_PANEL_W, panelH, ManiacUiTheme.BORDER);

        int titleY = y + MARGIN - 4;
        graphics.drawCenteredString(mc.font, Component.translatable("maniacmod.hud.rescue.title"),
            screenW / 2, titleY, ManiacUiTheme.TEXT_TITLE);
        ManiacUiTheme.divider(graphics, x + MARGIN, titleY + mc.font.lineHeight + 4, RESCUER_PANEL_W - MARGIN * 2);

        int barX = x + MARGIN;
        int barW = RESCUER_PANEL_W - MARGIN * 2;
        int barY = titleY + mc.font.lineHeight + 12;
        float fraction = ClientMatchState.rescueFraction();
        ManiacUiTheme.drawProgressBar(graphics, barX, barY, barW, BAR_H, fraction, ManiacUiTheme.BAR_FILL_NEUTRAL);
        graphics.drawCenteredString(mc.font, (int) (fraction * 100) + "%",
            screenW / 2, barY + (BAR_H - mc.font.lineHeight) / 2 + 1, ManiacUiTheme.TEXT_TITLE);
    }

    private static void renderHint(GuiGraphics graphics, Minecraft mc) {
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        graphics.drawCenteredString(mc.font, Component.translatable("maniacmod.hud.rescue.hint"),
            screenW / 2, screenH / 2 + OFFSET_FROM_CENTER, ManiacUiTheme.TEXT_ACCENT);
    }
}
