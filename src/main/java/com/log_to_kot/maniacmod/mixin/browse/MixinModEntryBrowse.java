package com.log_to_kot.maniacmod.mixin.browse;

import com.log_to_kot.maniacmod.client.screen.browse.ManiacBrowseStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.widget.ModListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Рядок мода в списку Forge: підкладка й рамка, текст і іконку версії малює Forge. */
@Mixin(ModListWidget.ModEntry.class)
public abstract class MixinModEntryBrowse {

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void maniacmod$base(GuiGraphics g, int index, int top, int left, int width, int height,
                                int mx, int my, boolean hovering, float pt, CallbackInfo ci) {
        if (ManiacBrowseStyle.isStyled()) ManiacBrowseStyle.cardBase(g, left - 2, top, width + 2, height, hovering);
    }

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void maniacmod$frame(GuiGraphics g, int index, int top, int left, int width, int height,
                                 int mx, int my, boolean hovering, float pt, CallbackInfo ci) {
        if (!ManiacBrowseStyle.isStyled()) return;
        com.log_to_kot.maniacmod.client.style.BloodFx.border1(g, left - 2, top, width + 2, height,
            hovering ? com.log_to_kot.maniacmod.client.style.BloodFx.MID : ManiacBrowseStyle.BORDER);
        g.fill(left - 2, top, left, top + height, hovering ? com.log_to_kot.maniacmod.client.style.BloodFx.BRIGHT : 0xFF1E2224);
    }
}
