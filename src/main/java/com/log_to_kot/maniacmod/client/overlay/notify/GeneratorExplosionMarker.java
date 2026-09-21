package com.log_to_kot.maniacmod.client.overlay.notify;

import com.log_to_kot.maniacmod.client.overlay.WorldToScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Червоний маркер вибухнулого генератора, видимий на ЛЮБІЙ відстані.
 *
 * ── Чому це екранна проєкція, а не світова геометрія ────────────────────
 * Генератор має бути видно з 1000+ блоків, але:
 *  • сервер відстежує сутності лише в радіусі огляду сервера, далі
 *    сутності в клієнта просто немає;
 *  • чанки за радіусом огляду клієнта не завантажені;
 *  • світова геометрія (промінь, куб) обрізається дальньою площиною
 *    камери, яка залежить від налаштувань гравця.
 * Екранна проєкція позиції від цього не залежить: беремо матриці
 * камери (див. {@link WorldToScreen}), множимо на позицію
 * та малюємо мітку в GUI. Якщо генератор за спиною чи за краєм екрана,
 * мітка притискається до краю й вказує напрямок.
 *
 * ── Час ────────────────────────────────────────────────────────────────
 * Як і {@code GeneratorCompletedOverlay}, порахований від
 * {@code System.currentTimeMillis()}, а не від тіків: мітка має
 * згаснути навіть коли відкрите меню й клієнтські тіки не рахуються.
 */
@OnlyIn(Dist.CLIENT)
public final class GeneratorExplosionMarker {

    private static final int RED = 0xFF3030;
    private static final int EDGE_MARGIN = 24;
    private static final int RADIUS = 7;

    /** Позиція → момент (мс), коли мітка гасне. Порядок вставки зберігається. */
    private static final Map<BlockPos, Long> activeUntilMs = new LinkedHashMap<>();

    private GeneratorExplosionMarker() {}

    public static void show(BlockPos pos, int durationTicks) {
        activeUntilMs.put(pos.immutable(), System.currentTimeMillis() + durationTicks * 50L);
    }

    /** Викликається зі {@code ClientMatchState.reset()} — кінець матчу гасить усе. */
    public static void reset() {
        activeUntilMs.clear();
    }

    public static void render(GuiGraphics graphics) {
        if (activeUntilMs.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        Iterator<Map.Entry<BlockPos, Long>> it = activeUntilMs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> entry = it.next();
            long leftMs = entry.getValue() - now;
            if (leftMs <= 0) {
                it.remove();
                continue;
            }
            drawOne(graphics, mc, entry.getKey(), leftMs, width, height, now);
        }
    }

    private static void drawOne(GuiGraphics graphics, Minecraft mc, BlockPos pos,
                                long leftMs, int width, int height, long now) {
        double wx = pos.getX() + 0.5;
        double wy = pos.getY() + 1.5;
        double wz = pos.getZ() + 0.5;

        WorldToScreen.Point point = WorldToScreen.project(wx, wy, wz, width, height, EDGE_MARGIN);
        if (point == null) return;
        int sx = point.x();
        int sy = point.y();

        // Пульсація й швидке згасання в останні 400 мс.
        double pulse = 0.65 + 0.35 * Math.sin(now / 90.0);
        double fade = Math.min(1.0, leftMs / 400.0);
        int alpha = (int) Math.max(30, Math.min(255, 255 * pulse * fade));
        int color = (alpha << 24) | RED;

        for (int dy = -RADIUS; dy <= RADIUS; dy++) {
            int half = RADIUS - Math.abs(dy);
            graphics.fill(sx - half, sy + dy, sx + half + 1, sy + dy + 1, color);
        }

        int meters = (int) Math.round(WorldToScreen.distanceXZ(wx, wz));
        String label = Component.translatable("maniacmod.hud.generator.exploded", meters).getString();
        int textColor = (Math.max(90, alpha) << 24) | 0xFFFFFF;
        graphics.drawCenteredString(mc.font, label, sx, sy + RADIUS + 4, textColor);
    }
}
