package com.log_to_kot.maniacmod.client.evac;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.entity.ClimbCarrierEntity;
import com.log_to_kot.maniacmod.entity.EvacRopeEntity;
import com.log_to_kot.maniacmod.entity.ReceiverEntity;
import com.log_to_kot.maniacmod.net.s2c.evac.EvacStatusPacket.Step;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Підказки евакуації над прицілом (за зразком {@code GeneratorHintOverlay}):
 * <ul>
 *   <li>приціл на приймачі — «ПКМ — відкрити приймач» + стан (немає живлення / можна викликати
 *       / вже викликано);</li>
 *   <li>приціл на готовій мотузці — «ПКМ — залізти»;</li>
 *   <li>гравець ЛІЗЕ — вертикальна шкала висоти праворуч (маркер піднімається до вертольота)
 *       і нагадування «Shift — зіскочити».</li>
 * </ul>
 * Чисто клієнтська косметика: що можна, а що ні, вирішує сервер; тут лише показ того, що вже
 * відомо з прицілу, фази й {@link ClientEvac}.
 */
@OnlyIn(Dist.CLIENT)
public final class EvacHintOverlay {

    private static final int GREEN = 0xFF7CFC9A, AMBER = 0xFFE0A030, GREY = 0xFF8E97A5;
    private static final int PAD = 9, ABOVE_CROSSHAIR = 46, MIN_W = 190;
    /** Мотузка вище цього відступу від верху не рахується (див. EvacModule.TOP_MARGIN). */
    private static final double TOP_MARGIN = 1.2;

    private static final long[] lastNanos = new long[1];
    private static float hintAppear = 0f;
    private static float climbAppear = 0f;
    private static float climbShown = 0f;                 // плавний прогрес шкали
    private static String lastTitle = "", lastSub = "";
    private static int lastAccent = GREEN;

    private EvacHintOverlay() {}

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        float dt = UiAnim.frameDt(lastNanos);
        boolean ui = mc.player != null && mc.screen == null && !mc.options.hideGui
            && ClientMatchState.isSurvivor() && ClientMatchState.isGameplay();

        Entity vehicle = ui ? mc.player.getVehicle() : null;
        boolean climbing = vehicle instanceof ClimbCarrierEntity;

        // ── Підказка над прицілом ────────────────────────────────────────
        boolean hint = false;
        if (ui && !climbing && mc.hitResult instanceof EntityHitResult hit) {
            hint = resolveHint(hit.getEntity());
        }
        hintAppear = UiAnim.approach(hintAppear, hint ? 1f : 0f, hint ? 11f : 16f, dt);
        if (hintAppear > 0.02f) drawHint(g, mc.font, UiAnim.easeOutCubic(UiAnim.clamp01(hintAppear)));

        // ── Шкала лазіння ────────────────────────────────────────────────
        climbAppear = UiAnim.approach(climbAppear, climbing ? 1f : 0f, climbing ? 8f : 12f, dt);
        if (climbing) climbShown = UiAnim.approach(climbShown, climbProgress(mc), 14f, dt);
        else if (climbAppear < 0.02f) climbShown = 0f;
        if (climbAppear > 0.02f) drawClimb(g, mc.font, UiAnim.easeOutCubic(UiAnim.clamp01(climbAppear)), climbShown);
    }

    // ─────────────────────────────────────────────────────────────────────

    /** Заповнює текст/колір підказки для сутності під прицілом; false — нічого показувати. */
    private static boolean resolveHint(Entity target) {
        int step = ClientEvac.step();
        if (target instanceof ReceiverEntity) {
            GamePhase phase = ClientMatchState.phase();
            lastTitle = Component.translatable("maniacmod.evac.hint.receiver").getString();
            if (phase == GamePhase.POWERED && step == Step.IDLE) {
                lastSub = Component.translatable("maniacmod.evac.hint.receiver.ready").getString();
                lastAccent = GREEN;
            } else if (phase == GamePhase.HUNT) {
                lastSub = Component.translatable("maniacmod.evac.hint.receiver.off").getString();
                lastAccent = GREY;
            } else {
                lastSub = Component.translatable("maniacmod.evac.hint.receiver.called").getString();
                lastAccent = AMBER;
            }
            return true;
        }
        if (target instanceof EvacRopeEntity rope && rope.isFullyDropped() && step == Step.CLIMB) {
            lastTitle = Component.translatable("maniacmod.evac.hint.rope").getString();
            lastSub = Component.translatable("maniacmod.evac.hint.rope.sub").getString();
            lastAccent = GREEN;
            return true;
        }
        return false;
    }

    private static void drawHint(GuiGraphics g, Font font, float ease) {
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int w = Math.max(MIN_W, Math.max(font.width(lastTitle), font.width(lastSub)) + PAD * 2 + 12);
        int h = PAD * 2 + 10 + 4 + 9;
        int x = sw / 2 - w / 2, y = sh / 2 - ABOVE_CROSSHAIR - h / 2;

        GeneratorFuelUiTheme.beginAppear(g, sw / 2f, y + h / 2f, ease, 10f);
        GeneratorFuelUiTheme.drawPanel(g, x, y, w, h);
        float breathe = 0.5f + 0.5f * Mth.sin(System.currentTimeMillis() / 300f);
        UiFx.glow(g, x, y, w, h, lastAccent, 0.18f + 0.22f * breathe, 3);
        UiFx.brackets(g, x, y, w, h, GeneratorFuelUiTheme.alpha(lastAccent, 0.85f), 5, breathe, 2);
        g.fill(x + PAD, y + PAD + 1, x + PAD + 3, y + PAD + 8, lastAccent);
        g.drawString(font, lastTitle, x + PAD + 8, y + PAD, GeneratorFuelUiTheme.TEXT, false);
        g.drawString(font, lastSub, x + PAD + 8, y + PAD + 14, lastAccent == GREY ? GREY : lastAccent, false);
        GeneratorFuelUiTheme.endAppear(g);
    }

    // ── Лазіння ──────────────────────────────────────────────────────────

    /** 0..1: від землі до верху мотузки. Мотузку шукаємо поруч — окремого пакета не треба. */
    private static float climbProgress(Minecraft mc) {
        var p = mc.player;
        var ropes = mc.level.getEntitiesOfClass(EvacRopeEntity.class, p.getBoundingBox().inflate(6, 48, 6));
        if (ropes.isEmpty()) return climbShown;
        EvacRopeEntity rope = ropes.get(0);
        double span = Math.max(1.0, rope.maxLength() - TOP_MARGIN);
        return (float) Mth.clamp((p.getY() - rope.getY()) / span, 0.0, 1.0);
    }

    private static void drawClimb(GuiGraphics g, Font font, float ease, float progress) {
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int barH = 120, barW = 8;
        int panelW = 44, panelH = barH + 46;
        int x = sw - panelW - 14, y = sh / 2 - panelH / 2;

        g.pose().pushPose();
        g.pose().translate((1f - ease) * (panelW + 20), 0f, 0f);       // виїжджає з правого краю
        GeneratorFuelUiTheme.drawPanel(g, x, y, panelW, panelH);

        int bx = x + panelW / 2 - barW / 2, by = y + 18;
        GeneratorFuelUiTheme.drawSlot(g, bx, by, barW, barH, ManiacUiTheme.BORDER);
        int fillH = Math.round((barH - 2) * progress);
        int top = by + barH - 1 - fillH;
        g.fill(bx + 1, top, bx + barW - 1, by + barH - 1, GeneratorFuelUiTheme.alpha(GREEN, 0.7f));

        // Маркер гравця на краю заливки.
        float pulse = 0.5f + 0.5f * Mth.sin(System.currentTimeMillis() / 160f);
        g.fill(bx - 3, top - 1, bx + barW + 3, top + 2, GeneratorFuelUiTheme.mix(GREEN, 0xFFFFFFFF, pulse));
        UiFx.glow(g, bx - 3, top - 1, barW + 6, 3, GREEN, 0.5f, 3);

        // Вертоліт угорі: «ціль».
        String heli = "▲";
        g.drawString(font, heli, x + panelW / 2 - font.width(heli) / 2, y + 6, GeneratorFuelUiTheme.TEXT, false);

        String pct = Math.round(progress * 100f) + "%";
        g.drawString(font, pct, x + panelW / 2 - font.width(pct) / 2, by + barH + 5, GREEN, false);
        g.pose().popPose();

        // Нагадування внизу екрана (лише поки лізе).
        Component shift = Component.translatable("maniacmod.evac.hint.climb_shift");
        int tw = font.width(shift);
        int ty = sh - 62;
        g.pose().pushPose();
        g.pose().translate(0f, (1f - ease) * 12f, 0f);
        g.fill(sw / 2 - tw / 2 - 6, ty - 3, sw / 2 + tw / 2 + 6, ty + 11, 0xAA0A0C0B);
        g.drawCenteredString(font, shift, sw / 2, ty, GREY);
        g.pose().popPose();
    }
}
