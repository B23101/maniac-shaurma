package com.log_to_kot.maniacmod.client.evac;

import com.log_to_kot.maniacmod.client.style.CircleFx;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Банер «Ім'я втік! 3/6»: з'їжджає зверху з відскоком, тримається, плавно їде геть.
 * Усередині — кільце-лічильник, що ДОМАЛЬОВУЄТЬСЯ від попереднього значення до нового,
 * і промінь, що пробігає по панелі в момент появи.
 *
 * <p>Малюється під {@link EvacHudOverlay}, не перекриваючи його. Дані — з
 * {@link ClientEvac#lastEscape()}; нова втеча просто перезапускає анімацію з нового часу,
 * черги немає (рідкісна подія, а свіжіша важливіша за застарілу).</p>
 */
@OnlyIn(Dist.CLIENT)
public final class EscapeToastOverlay {

    private static final long IN_MS = 520, HOLD_MS = 3600, OUT_MS = 420;
    private static final int W = 230, H = 52;
    private static final int Y = 62;
    private static final int GREEN = 0xFF7CFC9A;

    private EscapeToastOverlay() {}

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        ClientEvac.Escape e = ClientEvac.lastEscape();
        if (e == null || mc.player == null || mc.options.hideGui) return;

        long age = System.currentTimeMillis() - e.atMs();
        if (age < 0 || age > IN_MS + HOLD_MS + OUT_MS) return;

        // 0 → 1 на вході (з відскоком), 1 на утриманні, 1 → 0 на виході.
        float in = UiAnim.easeOutBack(UiAnim.clamp01(age / (float) IN_MS), 1.1f);
        float out = 1f - UiAnim.easeInOutCubic(UiAnim.clamp01((age - IN_MS - HOLD_MS) / (float) OUT_MS));
        float k = Math.min(in, out);

        int sw = mc.getWindow().getGuiScaledWidth();
        int x = sw / 2 - W / 2;
        Font font = mc.font;

        g.pose().pushPose();
        g.pose().translate(0f, (k - 1f) * (H + Y), 0f);          // виїжджає з-за верхнього краю

        GeneratorFuelUiTheme.drawPanel(g, x, Y, W, H);
        float glowK = 1f - UiAnim.clamp01((age - IN_MS) / 1200f);
        UiFx.glow(g, x, Y, W, H, GREEN, glowK, 4);
        UiFx.scanBeam(g, x, Y, W, H, UiAnim.clamp01(age / 800f), 26, GREEN, 60);
        UiFx.brackets(g, x, Y, W, H, GeneratorFuelUiTheme.alpha(GREEN, 0.9f), 6,
            1f - UiAnim.clamp01(age / 600f), 4);

        // ── Кільце-лічильник зліва ───────────────────────────────────────
        float cx = x + 30, cy = Y + H / 2f, r = 17f;
        CircleFx.ring(g, cx, cy, r - 3.5f, r, 0xFF1A1F26);
        float from = e.total() <= 0 ? 0f : (e.escaped() - 1) / (float) e.total();
        float to = e.total() <= 0 ? 0f : e.escaped() / (float) e.total();
        float fill = Mth.lerp(UiAnim.easeOutCubic(UiAnim.clamp01((age - 250) / 700f)), from, to);
        CircleFx.arc(g, cx, cy, r - 3.5f, r, CircleFx.TOP, CircleFx.TAU * fill,
            GeneratorFuelUiTheme.alpha(GREEN, 0.7f), GREEN, 1f, 1f);
        String num = Integer.toString(e.escaped());
        g.drawString(font, num, Math.round(cx - font.width(num) / 2f), Math.round(cy - 8), 0xFFFFFFFF, false);
        String of = "/" + e.total();
        g.drawString(font, of, Math.round(cx - font.width(of) / 2f), Math.round(cy + 1), 0xFF8E97A5, false);

        // ── Текст ────────────────────────────────────────────────────────
        int tx = x + 60;
        String name = font.plainSubstrByWidth(e.name(), W - 70);
        g.drawString(font, name, tx, Y + 12, GeneratorFuelUiTheme.TEXT, false);
        g.drawString(font, Component.translatable("maniacmod.evac.toast.escaped"), tx, Y + 25, GREEN, false);
        g.drawString(font, Component.translatable("maniacmod.evac.toast.left", Math.max(0, e.total() - e.escaped())),
            tx, Y + 37, 0xFF8E97A5, false);

        g.pose().popPose();
    }
}
