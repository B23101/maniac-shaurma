package com.log_to_kot.maniacmod.mixin.browse;

import com.log_to_kot.maniacmod.client.screen.browse.ManiacBrowseStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Друга лінія захисту від ванільної «землі» у списках (світи, сервери, моди): перед кожним
 * кадром вимикаємо фон і верх/низ самого списку. Окремий клас — щоб збій тут не зачепив
 * основний міксин списку.
 */
@Mixin(AbstractSelectionList.class)
public abstract class MixinSelectionListFlatBrowse {

    @Shadow protected abstract void setRenderBackground(boolean render);

    @Shadow protected abstract void setRenderTopAndBottom(boolean render);

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void maniacmod$flat(GuiGraphics g, int mx, int my, float pt, CallbackInfo ci) {
        if (!ManiacBrowseStyle.hidesListDirt()) return;
        setRenderBackground(false);
        setRenderTopAndBottom(false);
    }
}
