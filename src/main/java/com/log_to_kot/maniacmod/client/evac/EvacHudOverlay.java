package com.log_to_kot.maniacmod.client.evac;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.net.s2c.evac.EvacStatusPacket.Step;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Екранна панель евакуації вгорі по центру: поточний крок («Знайди ракету» → «Вертоліт летить» →
 * «Лізь!» …), лічильник «втекло N/M» з фігурками і — коли мотузка готова — шкала часу до відльоту.
 *
 * <h3>Анімація</h3>
 * Поява/зникнення — плавний підйом із масштабом; висота панелі «доганяє» ціль (шкала часу
 * виїжджає вниз); на зміну кроку спалахує рамка; на втечу фігурка, що засвітилась,
 * пульсує; останні 10 секунд шкала червоніє й тремтить пульсом.
 *
 * <h3>Чого тут немає</h3>
 * Жодної логіки: усе, що показано, прийшло з {@code EvacStatusPacket} через {@link ClientEvac}.
 */
@OnlyIn(Dist.CLIENT)
public final class EvacHudOverlay {

    private static final int PAD = 8;
    private static final int TOP = 6;
    private static final int MIN_W = 236;
    private static final int PIP_W = 5, PIP_GAP = 2, PIP_H = 9;
    private static final int MAX_PIPS = 12;
    private static final int BASE_H = 34, TIMER_EXTRA = 16;

    private static final int GREEN = 0xFF7CFC9A, AMBER = 0xFFE0A030, RED = 0xFFE0503C, SILVER = 0xFFC2C9D6;

    private static final long[] lastNanos = new long[1];
    private static float appear = 0f;
    private static float height = BASE_H;
    private static int lastStep = Step.IDLE;
    private static long stepChangedMs = 0L;
    private static int lastEscaped = 0;
    private static long escapedChangedMs = 0L;

    private EvacHudOverlay() {}

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int step = ClientEvac.step();
        boolean visible = step != Step.IDLE && ClientMatchState.isGameplay() && !mc.options.hideGui
            && mc.player != null;

        float dt = UiAnim.frameDt(lastNanos);
        appear = UiAnim.approach(appear, visible ? 1f : 0f, visible ? 9f : 12f, dt);
        if (!visible && appear < 0.01f) {
            appear = 0f;
            lastStep = Step.IDLE;
            return;
        }

        long now = System.currentTimeMillis();
        if (step != lastStep && step != Step.IDLE) {
            lastStep = step;
            stepChangedMs = now;
        }
        int escaped = ClientEvac.escaped();
        if (escaped != lastEscaped) {
            if (escaped > lastEscaped) escapedChangedMs = now;
            lastEscaped = escaped;
        }

        boolean timer = step == Step.CLIMB && ClientEvac.secondsLeft() >= 0f;
        height = UiAnim.approach(height, BASE_H + (timer ? TIMER_EXTRA : 0), 10f, dt);

        Font font = mc.font;
        int total = ClientEvac.total();
        int pips = Math.min(total, MAX_PIPS);
        int pipsW = pips > 0 ? pips * (PIP_W + PIP_GAP) - PIP_GAP : 0;
        Component title = Component.translatable("maniacmod.evac.hud.step." + step);
        Component hint = Component.translatable("maniacmod.evac.hud.hint." + step);
        int w = Math.max(MIN_W, Math.max(font.width(hint) + pipsW + PAD * 3,
            font.width(title) + 70 + PAD * 2));
        w = Math.min(w, mc.getWindow().getGuiScaledWidth() - 16);

        int sw = mc.getWindow().getGuiScaledWidth();
        int x = sw / 2 - w / 2;
        int y = TOP;
        int h = Math.round(height);
        float ease = UiAnim.easeOutCubic(UiAnim.clamp01(appear));

        GeneratorFuelUiTheme.beginAppear(g, sw / 2f, y + h / 2f, ease, -14f);
        GeneratorFuelUiTheme.drawPanel(g, x, y, w, h);

        // Спалах рамки на зміну кроку (гасне за 1.4 с).
        float flash = 1f - UiAnim.clamp01((now - stepChangedMs) / 1400f);
        int accent = accentOf(step);
        UiFx.glow(g, x, y, w, h, accent, flash, 4);
        UiFx.scanBeam(g, x, y, w, h, UiAnim.clamp01((now - stepChangedMs) / 900f), 22, accent, 56);

        // ── Рядок 1: «дихаючий» маркер + заголовок + лічильник ───────────
        float breathe = 0.5f + 0.5f * Mth.sin(now / 260f);
        int dotX = x + PAD + 3, dotY = y + PAD + 4;
        g.fill(dotX - 3, dotY - 3, dotX + 3, dotY + 3, GeneratorFuelUiTheme.alpha(accent, 0.55f + 0.45f * breathe));
        UiFx.glow(g, dotX - 3, dotY - 3, 6, 6, accent, 0.35f + 0.5f * breathe, 3);
        g.drawString(font, title, x + PAD + 12, y + PAD, GeneratorFuelUiTheme.TEXT, false);

        String counter = escaped + "/" + total;
        float pulse = 1f - UiAnim.clamp01((now - escapedChangedMs) / 900f);
        int counterColor = escaped > 0 ? GeneratorFuelUiTheme.mix(GREEN, 0xFFFFFFFF, pulse * 0.7f) : SILVER;
        int cw = font.width(counter);
        g.drawString(font, counter, x + w - PAD - cw, y + PAD, counterColor, false);

        // ── Рядок 2: підказка + фігурки ──────────────────────────────────
        int row2 = y + PAD + 13;
        g.drawString(font, hint, x + PAD, row2, ManiacUiTheme.TEXT_BODY, false);
        drawPips(g, x + w - PAD - pipsW, row2 - 1, pips, Math.min(escaped, pips), pulse, now);

        // ── Рядок 3: шкала часу до відльоту ──────────────────────────────
        if (timer && height > BASE_H + 6) {
            float left = ClientEvac.secondsLeft();
            int totalSec = Math.max(1, ClientEvac.secondsTotal());
            float frac = UiAnim.clamp01(left / totalSec);
            boolean urgent = left <= 10f;
            int color = urgent ? RED : left <= 30f ? AMBER : SILVER;
            int barY = y + BASE_H + 2, barH = 6;
            int timeW = font.width("0:00") + 6;
            int barX = x + PAD, barW = w - PAD * 2 - timeW;
            GeneratorFuelUiTheme.drawBar(g, barX, barY, barW, barH, frac);
            int fillW = Math.round(barW * frac);
            if (urgent) {
                float blink = 0.5f + 0.5f * Mth.sin(now / 110f);
                g.fill(barX + 1, barY + 1, barX + Math.max(1, fillW) - 1, barY + barH - 1,
                    GeneratorFuelUiTheme.alpha(color, 0.35f + 0.4f * blink));
            } else {
                g.fill(barX + 1, barY + 1, barX + Math.max(1, fillW) - 1, barY + barH - 1,
                    GeneratorFuelUiTheme.alpha(color, 0.55f));
            }
            UiFx.barHead(g, barX, barY, fillW, barH, color, 0.8f);
            int sec = (int) Math.ceil(left);
            String time = (sec / 60) + ":" + String.format("%02d", sec % 60);
            g.drawString(font, time, x + w - PAD - font.width(time), barY - 1, color, false);
        }
        GeneratorFuelUiTheme.endAppear(g);
    }

    /** Ряд фігурок: втекла — зелена зі світінням (щойно втекла — пульсує), решта — порожні. */
    private static void drawPips(GuiGraphics g, int x, int y, int pips, int filled, float pulse, long now) {
        for (int i = 0; i < pips; i++) {
            int px = x + i * (PIP_W + PIP_GAP);
            boolean on = i < filled;
            boolean fresh = on && i == filled - 1 && pulse > 0f;
            if (on) {
                int c = fresh ? GeneratorFuelUiTheme.mix(GREEN, 0xFFFFFFFF, pulse) : GREEN;
                g.fill(px + 1, y, px + PIP_W - 1, y + 3, c);                    // голова
                g.fill(px, y + 4, px + PIP_W, y + PIP_H, c);                     // тулуб
                if (fresh) UiFx.glow(g, px, y, PIP_W, PIP_H, GREEN, pulse, 3);
            } else {
                int c = 0xFF3A424C;
                ManiacUiTheme.border1px(g, px + 1, y, PIP_W - 2, 3, c);
                ManiacUiTheme.border1px(g, px, y + 4, PIP_W, PIP_H - 4, c);
            }
        }
    }

    private static int accentOf(int step) {
        return switch (step) {
            case Step.CALLED -> AMBER;
            case Step.INBOUND, Step.ROPE_DROP -> SILVER;
            case Step.CLIMB -> GREEN;
            case Step.DEPARTING -> RED;
            default -> SILVER;
        };
    }
}
