package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.items.CrowbarItem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.IItemDecorator;

/**
 * Число міцності лома («67%») у правому нижньому куті іконки. Той самий
 * механізм і те саме розташування, що {@link FuelCanisterChargeDecorator},
 * — щоб гравець читав обидва предмети однаково. Показує саме ту
 * зміну, яку просив дизайн: після удару по капкану видно −33%.
 *
 * Пороги кольору: понад 50% — нейтральний, 20–50% — попередження,
 * менше 20% (тобто останній удар) — небезпека.
 */
@OnlyIn(Dist.CLIENT)
public final class CrowbarDurabilityDecorator implements IItemDecorator {

    private static final int WARN_ABOVE_PERCENT = 50;
    private static final int DANGER_BELOW_PERCENT = 20;
    private static final float TEXT_Z = 200f;
    private static final float TEXT_SCALE = 0.65f;

    @Override
    public boolean render(GuiGraphics graphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        if (!(stack.getItem() instanceof CrowbarItem)) return false;

        int durability = CrowbarItem.getDurability(stack);
        String text = durability + "%";
        int textWidth = font.width(text);

        float right = xOffset + 16f;
        float bottom = yOffset + 16f;

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(right, bottom, TEXT_Z);
        pose.scale(TEXT_SCALE, TEXT_SCALE, 1f);
        graphics.drawString(font, text, -textWidth - 1, -font.lineHeight, colorFor(durability), true);
        pose.popPose();
        return false;
    }

    private static int colorFor(int durability) {
        if (durability < DANGER_BELOW_PERCENT) return ManiacUiTheme.BAR_FILL_DANGER;
        if (durability <= WARN_ABOVE_PERCENT) return ManiacUiTheme.BAR_FILL_WARN;
        return ManiacUiTheme.BAR_FILL_NEUTRAL;
    }
}
