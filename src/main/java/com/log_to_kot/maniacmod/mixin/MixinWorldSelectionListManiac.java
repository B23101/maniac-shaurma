package com.log_to_kot.maniacmod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/** Те саме, що {@link MixinServerSelectionListManiac}, для списку світів. */
@Mixin(WorldSelectionList.class)
public abstract class MixinWorldSelectionListManiac extends ObjectSelectionList<WorldSelectionList.Entry> {

    protected MixinWorldSelectionListManiac(Minecraft mc, int w, int h, int y0, int y1, int itemH) {
        super(mc, w, h, y0, y1, itemH);
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void maniacmod$flat(SelectWorldScreen screen, Minecraft mc, int w, int h, int y0, int y1,
                                int itemH, Supplier<String> filter, WorldSelectionList old, CallbackInfo ci) {
        this.setRenderBackground(false);
        this.setRenderTopAndBottom(false);
    }
}
