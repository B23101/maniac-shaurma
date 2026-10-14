package com.log_to_kot.maniacmod.mixin.browse;

import com.log_to_kot.maniacmod.client.screen.browse.ManiacBrowseStyle;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.widget.ScrollPanel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Панель інформації про мод (Forge ScrollPanel): темна підкладка замість «землі». */
@Mixin(ScrollPanel.class)
public abstract class MixinScrollPanelBrowse {

    @Shadow(remap = false) @Final protected int left;
    @Shadow(remap = false) @Final protected int top;
    @Shadow(remap = false) @Final protected int right;
    @Shadow(remap = false) @Final protected int bottom;

    @Inject(method = "drawBackground", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void maniacmod$bg(GuiGraphics g, Tesselator tess, float pt, CallbackInfo ci) {
        if (!ManiacBrowseStyle.isStyled()) return;
        ManiacBrowseStyle.panelBackground(g, left, top, right, bottom);
        ci.cancel();
    }
}
