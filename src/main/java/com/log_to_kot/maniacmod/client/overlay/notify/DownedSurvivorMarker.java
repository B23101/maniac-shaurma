package com.log_to_kot.maniacmod.client.overlay.notify;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.overlay.WorldToScreen;
import com.log_to_kot.maniacmod.net.s2c.matchstate.DownedSurvivorsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
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
 * Голова самого гравця (його скін) у рамці кольору стану, під нею відстань і час до смерті.
 * Того, кого маньяк несе на плечі, не позначаємо зовсім: він і так у маньяка перед очима,
 * а мітка над ним лише заважала б.
 * Спокійно пульсує, а в останні {@link #URGENT_MS} блимає червоним — щоб
 * було видно, що вже не встигаєш.
 *
 * Проєкція — {@link WorldToScreen}: мітка видна крізь стіни, з будь-якої
 * відстані, і притискається до краю екрана, якщо союзник за спиною.
 */
@OnlyIn(Dist.CLIENT)
public final class DownedSurvivorMarker {

    private static final int EDGE_MARGIN = 26;
    /** Півсторона голови-іконки, пікселі (голова 2*HEAD_HALF px). */
    private static final int HEAD_HALF = 8;

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
        boolean timed = entry.timed();
        boolean urgent = timed && leftMs < URGENT_MS;
        com.log_to_kot.maniacmod.hooks.HookStatus status =
            com.log_to_kot.maniacmod.hooks.HookStatus.fromCode(entry.status());
        // На плечі маньяка — без мітки (див. опис класу).
        if (status == com.log_to_kot.maniacmod.hooks.HookStatus.CARRIED) return;

        double pulse = urgent
            ? 0.45 + 0.55 * Math.abs(Math.sin(now / 130.0))
            : 0.80 + 0.20 * Math.sin(now / 320.0);
        int alpha = (int) Math.max(60, Math.min(255, 255 * pulse));
        // Колір ромба = колір контуру жертви (лежить — золотий, висить — червоний).
        int rgb = urgent ? URGENT_RGB : switch (status) {
            case CARRIED -> 0xC060FF;
            case HOOKED -> 0xFF4040;
            default -> CALM_RGB;
        };

        int cx = point.x();
        int cy = point.y();
        // Рамка кольору стану + голова гравця всередині.
        int x0 = cx - HEAD_HALF, y0 = cy - HEAD_HALF, size = HEAD_HALF * 2;
        graphics.fill(x0 - 2, y0 - 2, x0 + size + 2, y0 + size + 2, (alpha << 24) | 0x101010);
        graphics.fill(x0 - 1, y0 - 1, x0 + size + 1, y0 + size + 1, (alpha << 24) | rgb);
        PlayerFaceRenderer.draw(graphics, skinOf(entry.id()), x0, y0, size);

        int meters = (int) Math.round(WorldToScreen.distanceXZ(wx, wz));
        long seconds = (leftMs + 999) / 1000;
        String label = timed
            ? meters + " м · " + (seconds / 60) + ":" + String.format("%02d", seconds % 60)
            : meters + " м · " + net.minecraft.network.chat.Component
                .translatable("maniacmod.hook.status." + entry.status()).getString();
        // Таймер до смерті — ЗАВЖДИ червоні цифри (а не лише в останні
        // секунди): лежачий має бачити, скільки йому лишилось, з першого
        // кадру, і це має читатись на всю карту без пояснень.
        int textColor = (Math.max(140, alpha) << 24) | URGENT_RGB;
        graphics.drawCenteredString(mc.font, label, cx, cy + HEAD_HALF + 5, textColor);
    }

    private static ResourceLocation skinOf(UUID uuid) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Player player = mc.level.getPlayerByUUID(uuid);
            if (player instanceof AbstractClientPlayer clientPlayer) {
                return clientPlayer.getSkinTextureLocation();
            }
        }
        return DefaultPlayerSkin.getDefaultSkin(uuid);
    }
}
