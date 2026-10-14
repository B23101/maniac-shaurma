package com.log_to_kot.maniacmod.mixin.browse;

import com.log_to_kot.maniacmod.client.screen.browse.ManiacBrowseStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Картка світу: підкладка під ванільний вміст (HEAD) і рамка/акцент над ним (TAIL).
 * Дані світу (LevelSummary) ванільний render малює сам — читати їх окремо не потрібно.
 */
@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class MixinWorldListEntryBrowse {

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void maniacmod$base(GuiGraphics g, int index, int top, int left, int width, int height,
                                int mx, int my, boolean hovering, float pt, CallbackInfo ci) {
        if (ManiacBrowseStyle.isStyled()) ManiacBrowseStyle.cardBase(g, left, top, width, height, hovering);
    }

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void maniacmod$frame(GuiGraphics g, int index, int top, int left, int width, int height,
                                 int mx, int my, boolean hovering, float pt, CallbackInfo ci) {
        if (ManiacBrowseStyle.isStyled()) ManiacBrowseStyle.cardFrame(g, left, top, width, height, hovering);
    }
}
