package com.log_to_kot.maniacmod.client.overlay.actionprogress;

import com.log_to_kot.maniacmod.client.ClientInputHandler;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.net.s2c.matchstate.DownedSurvivorsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Порятунок лежачого — у стилі панелей генератора
 * ({@link GeneratorFuelUiTheme}): панель, рядок «назва — значення»,
 * роздільник, заглиблені бари з блиском, слідом і пульсом небезпеки.
 *
 *   • лежачий бачить час до смерті (бар порожніє, останні 10 с — червоний
 *     пульс) і, коли його піднімають, окремий бар підняття;
 *   • рятівник бачить бар підняття;
 *   • той, хто дивиться на лежачого, — хінт-панель «утримуй ПКМ».
 * Логіка/дані ті самі, що й раніше; змінено лише малювання й анімації.
 */
@OnlyIn(Dist.CLIENT)
public final class RescueOverlay {

    private static final int PANEL_W = 250;
    private static final int RESCUER_PANEL_W = 210;
    private static final int PAD = 10;
    private static final int BAR_H = 10;
    private static final int OFFSET_FROM_CENTER = 42;

    private static final long[] lastFrameNanos = new long[1];
    private static float appear = 0f;
    private static float timeShown = -1f;
    private static float timeGhost = -1f;
    private static float rescueShown = 0f;
    private static float rescueGhost = 0f;
    private static int lastView = 0; // 0 нічого, 1 лежачий, 2 рятівник, 3 хінт

    private RescueOverlay() {}

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int view = 0;
        if (ClientMatchState.isDowned(mc.player.getUUID())) {
            view = 1;
        } else if (ClientMatchState.isSurvivor()) {
            if (ClientMatchState.rescueActive() && !ClientMatchState.rescueAsVictim()) {
                view = 2;
            } else if (ClientInputHandler.isLookingAtDownedSurvivor()
                    && !ClientMatchState.survivorState().isCrawlOnly()) {
                view = 3;
            }
        }

        if (view == 0) { reset(); return; }
        if (view != lastView) { appear = 0f; lastView = view; }

        float dt = UiAnim.frameDt(lastFrameNanos);
        appear = UiAnim.approach(appear, 1f, 11f, dt);
        float ease = UiAnim.easeOutCubic(UiAnim.clamp01(appear));

        switch (view) {
            case 1 -> renderVictim(graphics, mc, ease, dt);
            case 2 -> renderRescuer(graphics, mc, ease, dt);
            default -> renderHint(graphics, mc, ease);
        }
    }

    private static void reset() {
        lastFrameNanos[0] = 0L;
        appear = 0f;
        timeShown = -1f;
        timeGhost = -1f;
        rescueShown = 0f;
        rescueGhost = 0f;
        lastView = 0;
    }

    // ── Лежачий ──────────────────────────────────────────────────────────

    private static void renderVictim(GuiGraphics graphics, Minecraft mc, float ease, float dt) {
        DownedSurvivorsPacket.Entry self = null;
        for (DownedSurvivorsPacket.Entry entry : ClientMatchState.downedEntries()) {
            if (entry.id().equals(mc.player.getUUID())) { self = entry; break; }
        }
        if (self == null) return;
        Font font = mc.font;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        boolean beingRescued = ClientMatchState.rescueActive() && ClientMatchState.rescueAsVictim();
        int panelH = PAD * 2 + 10 + 6 + 8 + BAR_H + 8 + (beingRescued ? BAR_H : 10);
        int x = screenW / 2 - PANEL_W / 2;
        int y = screenH / 2 + OFFSET_FROM_CENTER;

        long leftMs = ClientMatchState.downedMillisLeft(self);
        boolean timed = self.timed();
        boolean urgent = timed && leftMs < 10_000;
        float target = UiAnim.clamp01(ClientMatchState.downedFraction(self));
        timeShown = timeShown < 0f ? target : UiAnim.approach(timeShown, target, 14f, dt);
        timeGhost = timeGhost < 0f ? target : (target > timeGhost ? target : UiAnim.approach(timeGhost, target, 1.4f, dt));
        float urgentPulse = urgent ? 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 170.0) : 0f;

        GeneratorFuelUiTheme.beginAppear(graphics, x + PANEL_W / 2f, y + panelH / 2f, ease, 12f);
        GeneratorFuelUiTheme.drawPanel(graphics, x, y, PANEL_W, panelH);
        // Рамка небезпеки: завжди трохи червона, у критичні секунди пульсує.
        ManiacUiTheme.border1px(graphics, x, y, PANEL_W, panelH,
            GeneratorFuelUiTheme.alpha(GeneratorFuelUiTheme.FILL_DANGER, 0.55f + 0.45f * urgentPulse));

        long seconds = (leftMs + 999) / 1000;
        String time = timed
            ? (seconds / 60) + ":" + String.format("%02d", seconds % 60)
            : Component.translatable("maniacmod.hook.status." + self.status()).getString();
        int rowX = x + PAD;
        int rowW = PANEL_W - PAD * 2;
        int rowY = y + PAD;
        GeneratorFuelUiTheme.drawTitleRow(graphics, font,
            Component.translatable("maniacmod.hud.downed.title").getString(), time, rowX, rowY, rowW);
        GeneratorFuelUiTheme.divider(graphics, x, rowY + 10, PANEL_W);

        int barY = rowY + 10 + 8;
        int timeColor = GeneratorFuelUiTheme.mix(GeneratorFuelUiTheme.FILL_WARN, GeneratorFuelUiTheme.FILL_DANGER,
            urgent ? 0.5f + 0.5f * urgentPulse : 0f);
        GeneratorFuelUiTheme.drawBarFx(graphics, rowX, barY, rowW, BAR_H, timeShown, timeGhost, timeColor, urgentPulse * 0.25f);

        int row2Y = barY + BAR_H + 8;
        if (beingRescued) {
            float rt = UiAnim.clamp01(ClientMatchState.rescueFraction());
            rescueShown = UiAnim.approach(rescueShown, rt, 14f, dt);
            rescueGhost = rt > rescueGhost ? rt : UiAnim.approach(rescueGhost, rt, 1.4f, dt);
            GeneratorFuelUiTheme.drawBarFx(graphics, rowX, row2Y, rowW, BAR_H, rescueShown, rescueGhost,
                GeneratorFuelUiTheme.FILL, 0f);
            String label = Component.translatable("maniacmod.hud.downed.being_rescued").getString();
            graphics.drawString(font, label, screenW / 2 - font.width(label) / 2,
                row2Y + (BAR_H - font.lineHeight) / 2 + 1, GeneratorFuelUiTheme.TEXT, true);
        } else {
            rescueShown = 0f;
            rescueGhost = 0f;
            String hint = Component.translatable("maniacmod.hud.downed.hint").getString();
            graphics.drawString(font, hint, screenW / 2 - font.width(hint) / 2, row2Y + 1,
                GeneratorFuelUiTheme.HIT_ZONE, false);
        }
        GeneratorFuelUiTheme.endAppear(graphics);
    }

    // ── Рятівник ─────────────────────────────────────────────────────────

    private static void renderRescuer(GuiGraphics graphics, Minecraft mc, float ease, float dt) {
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int panelH = PAD * 2 + 10 + 8 + BAR_H;
        int x = screenW / 2 - RESCUER_PANEL_W / 2;
        int y = screenH / 2 + OFFSET_FROM_CENTER;

        float target = UiAnim.clamp01(ClientMatchState.rescueFraction());
        rescueShown = UiAnim.approach(rescueShown, target, 14f, dt);
        rescueGhost = target > rescueGhost ? target : UiAnim.approach(rescueGhost, target, 1.4f, dt);

        GeneratorFuelUiTheme.beginAppear(graphics, x + RESCUER_PANEL_W / 2f, y + panelH / 2f, ease, 12f);
        GeneratorFuelUiTheme.drawPanel(graphics, x, y, RESCUER_PANEL_W, panelH);
        int rowX = x + PAD;
        int rowW = RESCUER_PANEL_W - PAD * 2;
        int rowY = y + PAD;
        GeneratorFuelUiTheme.drawTitleRow(graphics, font,
            Component.translatable("maniacmod.hud.rescue.title").getString(),
            Math.round(rescueShown * 100f) + "%", rowX, rowY, rowW);
        GeneratorFuelUiTheme.divider(graphics, x, rowY + 10, RESCUER_PANEL_W);
        GeneratorFuelUiTheme.drawBarFx(graphics, rowX, rowY + 18, rowW, BAR_H, rescueShown, rescueGhost,
            GeneratorFuelUiTheme.FILL, 0f);
        GeneratorFuelUiTheme.endAppear(graphics);
    }

    // ── Хінт «утримуй ПКМ» ───────────────────────────────────────────────

    private static void renderHint(GuiGraphics graphics, Minecraft mc, float ease) {
        Font font = mc.font;
        String text = Component.translatable("maniacmod.hud.rescue.hint").getString();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int w = Math.max(200, font.width(text) + PAD * 2);
        int h = PAD * 2 + 10;
        int x = screenW / 2 - w / 2;
        int y = screenH / 2 + OFFSET_FROM_CENTER;

        GeneratorFuelUiTheme.beginAppear(graphics, x + w / 2f, y + h / 2f, ease, 10f);
        GeneratorFuelUiTheme.drawPanel(graphics, x, y, w, h);
        GeneratorFuelUiTheme.drawTitleLeft(graphics, font, text, x + PAD, y + PAD);
        GeneratorFuelUiTheme.endAppear(graphics);
    }
}
