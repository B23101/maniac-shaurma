package com.log_to_kot.maniacmod.client.barricade;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.items.CrowbarItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Напис під прицілом: у режимі будування — що робити далі; у грі виживому, що дивиться на
 * барикаду, — «потрібен лом» або «ПКМ — зламати». Суто клієнтський: усе потрібне вже є (приціл,
 * сутності барикад, предмет у руці); сервер перевіряє те саме при кліку.
 */
@OnlyIn(Dist.CLIENT)
public final class BarricadeHintOverlay {

    private static final int PADDING_X = 8;
    private static final int PADDING_Y = 4;
    private static final int OFFSET_BELOW_CROSSHAIR = 22;
    private static final int TEXT_OK = 0xFFB8F5C0;
    private static final int TEXT_FAIL = 0xFFFF6B6B;

    private BarricadeHintOverlay() {}

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui || mc.player == null) return;

        Component text;
        boolean ok = true;
        if (BarricadeBuildClient.isActive()) {
            text = Component.translatable(BarricadeBuildClient.hasPending()
                ? "maniacmod.barricade.hint_build_second" : "maniacmod.barricade.hint_build_first",
                BarricadeBuildClient.boardCount());
        } else if (ClientMatchState.isSurvivor() && BoardsAim.lookingAtBarricade(mc)) {
            ok = mc.player.getMainHandItem().getItem() instanceof CrowbarItem;
            text = Component.translatable(ok
                ? "maniacmod.barricade.hint_break" : "maniacmod.barricade.hint_need_crowbar");
        } else {
            return;
        }

        Font font = mc.font;
        int w = font.width(text) + PADDING_X * 2;
        int h = font.lineHeight + PADDING_Y * 2;
        int x = mc.getWindow().getGuiScaledWidth() / 2 - w / 2;
        int y = mc.getWindow().getGuiScaledHeight() / 2 + OFFSET_BELOW_CROSSHAIR;
        GeneratorFuelUiTheme.drawSlot(graphics, x, y, w, h,
            ok ? GeneratorFuelUiTheme.PANEL_BORDER : GeneratorFuelUiTheme.FILL_DANGER);
        graphics.drawString(font, text, x + PADDING_X, y + PADDING_Y, ok ? TEXT_OK : TEXT_FAIL, false);
    }
}
