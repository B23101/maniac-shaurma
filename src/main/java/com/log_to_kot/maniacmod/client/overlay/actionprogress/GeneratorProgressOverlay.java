package com.log_to_kot.maniacmod.client.overlay.actionprogress;

import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorProgressPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Прогрес-бар ремонту генератора біля прицілу.
 *
 * Стан приходить одним пакетом (GeneratorProgressPacket) і живе тут
 * доти, доки сервер не надішле hidden(). Оверлей нічого не рахує сам —
 * він лише малює те, що прислав сервер.
 *
 * v3-еквівалент: однойменний клас, але зі статичними полями, які
 * ніхто не скидав при виході з гри: бар лишався висіти на екрані
 * після завершення матчу.
 */
@OnlyIn(Dist.CLIENT)
public final class GeneratorProgressOverlay {

    private static GeneratorProgressPacket current = GeneratorProgressPacket.hidden();

    private GeneratorProgressOverlay() {}

    public static void accept(GeneratorProgressPacket packet) {
        current = packet;
    }

    /** Скидання при виході з матчу — викликається з ClientMatchState.reset(). */
    public static void reset() {
        current = GeneratorProgressPacket.hidden();
    }

    public static void render(GuiGraphics graphics) {
        if (!current.visible()) return;

        Minecraft mc = Minecraft.getInstance();
        int centerX = mc.getWindow().getGuiScaledWidth() / 2;
        int y = mc.getWindow().getGuiScaledHeight() / 2 + 16;

        int barWidth = 80;
        int left = centerX - barWidth / 2;
        int filled = Math.max(0, Math.min(barWidth, barWidth * current.stagePercent() / 100));

        // Стадія 0 — ремонт (жовтий), стадія 1 — бензин (синій).
        int colour = current.stage() == 0 ? 0xFFFFD54F : 0xFF4FC3F7;

        graphics.fill(left - 1, y - 1, left + barWidth + 1, y + 6, 0xAA000000);
        graphics.fill(left, y, left + filled, y + 5, colour);

        Component label = current.stage() == 0
            ? Component.translatable("maniacmod.hud.generator.repair",
                current.minigamesDone(), current.minigamesTotal())
            : Component.translatable("maniacmod.hud.generator.fuel", current.fuelPercent());

        graphics.drawCenteredString(mc.font, label, centerX, y - 11, 0xFFFFFFFF);
    }
}
