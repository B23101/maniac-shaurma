package com.log_to_kot.maniacmod.client.overlay.debug;

import com.log_to_kot.maniacmod.core.match.DebugMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Оверлей, що показує текст "DEBUG MODE" зверху по центру екрана,
 * коли дебаг-режим увімкнено (конфіг або runtime-toggle).
 *
 * <p>Малюється завжди (не залежить від фази матчу), щоб було видно
 * навіть у лобі.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class DebugOverlay {

    private DebugOverlay() {}

    public static void render(GuiGraphics graphics) {
        if (!DebugMode.enabled()) return;

        Minecraft mc = Minecraft.getInstance();
        int centerX = mc.getWindow().getGuiScaledWidth() / 2;
        int y = 2; // зовсім зверху

        Component label = Component.translatable("maniacmod.hud.debug_mode");
        graphics.drawCenteredString(mc.font, label, centerX, y, 0xFFFF5555);
    }
}
