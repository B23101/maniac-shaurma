package com.log_to_kot.maniacmod.client.traps;

import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.traps.TrapArchetype;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Напис під прицілом у режимі розміщення пастки: ЧОМУ тут не можна
 * ставити (червоним) або що робити далі (зеленим) — ще до натискання ПКМ.
 *
 * Джерело тексту — {@link TrapPlacementController#targetResult()}, тобто те
 * саме рішення, що фарбує квадрат, тож напис і колір не можуть розійтись.
 * Ключі перекладу — ті самі {@code maniacmod.trap.place_<причина>}, якими
 * сервер пише відмову в action bar, тому тексти однакові в обох місцях.
 */
@OnlyIn(Dist.CLIENT)
public final class TrapPlacementHintOverlay {

    private static final int PADDING_X = 8;
    private static final int PADDING_Y = 4;
    private static final int OFFSET_BELOW_CROSSHAIR = 22;

    private static final int TEXT_OK = 0xFFB8F5C0;
    private static final int TEXT_FAIL = 0xFFFF6B6B;

    private TrapPlacementHintOverlay() {}

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui) return;
        if (!TrapPlacementController.isActive()) return;

        boolean ok = TrapPlacementController.targetValid();
        Component text = ok ? okText() : failText(TrapPlacementController.targetResult());
        int color = ok ? TEXT_OK : TEXT_FAIL;

        Font font = mc.font;
        int w = font.width(text) + PADDING_X * 2;
        int h = font.lineHeight + PADDING_Y * 2;
        int x = mc.getWindow().getGuiScaledWidth() / 2 - w / 2;
        int y = mc.getWindow().getGuiScaledHeight() / 2 + OFFSET_BELOW_CROSSHAIR;

        GeneratorFuelUiTheme.drawSlot(graphics, x, y, w, h,
            ok ? GeneratorFuelUiTheme.PANEL_BORDER : GeneratorFuelUiTheme.FILL_DANGER);
        graphics.drawString(font, text, x + PADDING_X, y + PADDING_Y, color, false);
    }

    private static Component okText() {
        return Component.translatable(TrapPlacementController.isTwoPointMode()
            && TrapPlacementController.firstPoint() != null
            ? "maniacmod.trap.place_hint_ok_second" : "maniacmod.trap.place_hint_ok");
    }

    private static Component failText(TrapArchetype.PlacementResult reason) {
        return Component.translatable("maniacmod.trap.place_" + reason.name().toLowerCase(java.util.Locale.ROOT));
    }
}
