package com.log_to_kot.maniacmod.client.overlay.notify;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.overlay.WorldToScreen;
import com.log_to_kot.maniacmod.net.s2c.matchstate.DownedSurvivorsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * Мітка лежачого на ВСЮ карту: союзники знають, де рятувати і скільки
 * лишилось, а маньяк — де він лежить.
 *
 * ── Хто бачить ───────────────────────────────────────────────────────
 * Виживі і маньяк — так задано дизайном («підсвічується на всю карту —
 * його бачать і союзники, і маньяк», GAME_DESIGN.md, «Втрата свідомості»).
 * Це навмисна ціна за те, що ти лежиш: нікуди не сховатись. Глядачі мітки
 * не малюють. Свою мітку гравець не бачить — у нього є власна панель
 * ({@code RescueOverlay}).
 *
 * ── Вигляд ───────────────────────────────────────────────────────────
 * Ромб із хрестом («потрібна допомога»), під ним відстань і час до смерті.
 * Спокійно пульсує, а в останні {@link #URGENT_MS} блимає червоним — щоб
 * було видно, що вже не встигаєш.
 *
 * Проєкція — {@link WorldToScreen}: мітка видна крізь стіни, з будь-якої
 * відстані, і притискається до краю екрана, якщо союзник за спиною.
 */
@OnlyIn(Dist.CLIENT)
public final class DownedSurvivorMarker {

    private static final int EDGE_MARGIN = 26;
    private static final int RADIUS = 7;

    /** Скільки лишилось до смерті, коли мітка починає блимати. */
    private static final long URGENT_MS = 10_000;

    private static final int CALM_RGB = 0xFF8A3A;
    private static final int URGENT_RGB = 0xFF3030;

    private DownedSurvivorMarker() {}

    public static void render(GuiGraphics graphics) {
        if (!ClientMatchState.isSurvivor() && !ClientMatchState.isManiac()) return;
        var entries = ClientMatchState.downedEntries();
        if (entries.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        UUID self = mc.player.getUUID();

        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        long now = System.currentTimeMillis();

        for (DownedSurvivorsPacket.Entry entry : entries) {
            if (entry.id().equals(self)) continue;
            drawOne(graphics, mc, entry, width, height, now);
        }
    }

    private static void drawOne(GuiGraphics graphics, Minecraft mc, DownedSurvivorsPacket.Entry entry,
                                int width, int height, long now) {
        double wx = entry.x();
        double wy = entry.y() + 1.0; // над тілом, а не в підлозі
        double wz = entry.z();

        WorldToScreen.Point point = WorldToScreen.project(wx, wy, wz, width, height, EDGE_MARGIN);
        if (point == null) return;

        long leftMs = ClientMatchState.downedMillisLeft(entry);
        boolean urgent = leftMs < URGENT_MS;

        double pulse = urgent
            ? 0.45 + 0.55 * Math.abs(Math.sin(now / 130.0))
            : 0.80 + 0.20 * Math.sin(now / 320.0);
        int alpha = (int) Math.max(60, Math.min(255, 255 * pulse));
        int rgb = urgent ? URGENT_RGB : CALM_RGB;

        int cx = point.x();
        int cy = point.y();
        drawDiamond(graphics, cx, cy, RADIUS + 1, (alpha << 24) | 0x101010);
        drawDiamond(graphics, cx, cy, RADIUS, (alpha << 24) | rgb);

        // Білий хрест усередині — «потрібна допомога».
        int cross = (alpha << 24) | 0xFFFFFF;
        graphics.fill(cx - 1, cy - 4, cx + 2, cy + 5, cross);
        graphics.fill(cx - 4, cy - 1, cx + 5, cy + 2, cross);

        int meters = (int) Math.round(WorldToScreen.distanceXZ(wx, wz));
        long seconds = (leftMs + 999) / 1000;
        String label = meters + " м · " + (seconds / 60) + ":" + String.format("%02d", seconds % 60);
        int textColor = (Math.max(110, alpha) << 24) | (urgent ? URGENT_RGB : 0xFFFFFF);
        graphics.drawCenteredString(mc.font, label, cx, cy + RADIUS + 5, textColor);
    }

    private static void drawDiamond(GuiGraphics graphics, int cx, int cy, int radius, int argb) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = radius - Math.abs(dy);
            graphics.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, argb);
        }
    }
}
