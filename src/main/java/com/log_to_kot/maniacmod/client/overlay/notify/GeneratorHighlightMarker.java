package com.log_to_kot.maniacmod.client.overlay.notify;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.overlay.WorldToScreen;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorHighlightPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Підсвітка генераторів для ВИЖИВОГО (клавіша 5): мітка на кожному
 * генераторі, колір якої показує його стан.
 *
 * <pre>
 *   білий   — не полагоджений, ніхто не працює
 *   жовтий  — хтось лагодить або заливає бензин просто зараз
 *   червоний— щойно вибухнув
 *   зелений — полагоджений
 * </pre>
 *
 * ── Чому видно лише тому, хто натиснув ────────────────────────────────
 * Пакет {@code GeneratorHighlightPacket} сервер шле одному гравцю (тому,
 * хто натиснув 5), а цей клас малює суто на ЙОГО клієнті. Ванільне
 * світіння сутності ({@code setGlowingTag}) для цього не годиться: воно
 * видиме всім, хто бачить сутність, — включно з маньяком. Тож серверного
 * стану «підсвічено» немає, і чужий клієнт про це просто не знає.
 *
 * ── Проєкція ─────────────────────────────────────────────────────────
 * Мітка на екрані, а не у світі: генератори видно крізь стіни й з будь-якої
 * відстані (див. {@link WorldToScreen}).
 *
 * ── Кольори ──────────────────────────────────────────────────────────
 * З {@link ClientMatchState#highlightColor} — єдине місце палітри.
 *
 * ── Час ──────────────────────────────────────────────────────────────
 * Від {@code System.currentTimeMillis()}, а не від тіків клієнта:
 * підсвітка має згаснути вчасно й при відкритому меню, коли тіки не йдуть.
 */
@OnlyIn(Dist.CLIENT)
public final class GeneratorHighlightMarker {

    private static final int EDGE_MARGIN = 24;
    private static final int RADIUS = 6;
    private static final int OUTLINE = 1;

    /** Момент (мс), коли підсвітка гасне. 0 — не активна. */
    private static long activeUntilMs = 0;

    private static List<GeneratorHighlightPacket.Entry> entries = List.of();

    private GeneratorHighlightMarker() {}

    /** Викликається з {@code ClientPacketHandler} на кожен {@code GeneratorHighlightPacket}. */
    public static void show(List<GeneratorHighlightPacket.Entry> newEntries, int durationTicks) {
        entries = List.copyOf(newEntries);
        activeUntilMs = System.currentTimeMillis() + durationTicks * 50L;
    }

    /** Викликається зі {@code ClientMatchState.reset()} — кінець матчу гасить усе. */
    public static void reset() {
        activeUntilMs = 0;
        entries = List.of();
    }

    private static boolean isActive() {
        return activeUntilMs != 0 && System.currentTimeMillis() < activeUntilMs;
    }

    public static void render(GuiGraphics graphics) {
        if (!isActive()) {
            if (activeUntilMs != 0) reset();
            return;
        }
        // Підсвітка — лише для виживого. Роль могла змінитись, поки мітка
        // висіла (наприклад дебаг-morph): не малюємо чужій ролі.
        if (!ClientMatchState.isSurvivor()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        long leftMs = activeUntilMs - System.currentTimeMillis();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        // Плавне згасання в останні 500 мс, щоб мітка не «зникала різко».
        double fade = Math.min(1.0, leftMs / 500.0);

        for (GeneratorHighlightPacket.Entry entry : entries) {
            drawOne(graphics, mc, entry, fade, width, height);
        }
    }

    private static void drawOne(GuiGraphics graphics, Minecraft mc,
                                GeneratorHighlightPacket.Entry entry,
                                double fade, int width, int height) {
        double wx = entry.pos().getX() + 0.5;
        double wy = entry.pos().getY() + 1.5;
        double wz = entry.pos().getZ() + 0.5;

        WorldToScreen.Point point = WorldToScreen.project(wx, wy, wz, width, height, EDGE_MARGIN);
        if (point == null) return;

        int alpha = (int) Math.max(40, Math.min(255, 255 * fade));
        int rgb = ClientMatchState.highlightColor(entry.state()) & 0x00FFFFFF;
        int fill = (alpha << 24) | rgb;
        int outline = (alpha << 24) | 0x101010;

        // Ромб із темною обводкою: без обводки білий ромб губиться на
        // світлому небі, а жовтий — на піску.
        drawDiamond(graphics, point.x(), point.y(), RADIUS + OUTLINE, outline);
        drawDiamond(graphics, point.x(), point.y(), RADIUS, fill);

        int meters = (int) Math.round(WorldToScreen.distanceXZ(wx, wz));
        int textColor = (Math.max(90, alpha) << 24) | 0xFFFFFF;
        graphics.drawCenteredString(mc.font, meters + " м", point.x(), point.y() + RADIUS + 5, textColor);
    }

    private static void drawDiamond(GuiGraphics graphics, int cx, int cy, int radius, int argb) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = radius - Math.abs(dy);
            graphics.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, argb);
        }
    }
}
