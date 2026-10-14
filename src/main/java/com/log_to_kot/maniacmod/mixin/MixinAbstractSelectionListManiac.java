package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Прибирає ванільну білу рамку вибору рядка в списках серверів і світів:
 * вибраний рядок тепер підсвічує сама картка ({@code ManiacMenuCards}).
 * Решту ванільного (скрол, клавіші, клік) не чіпаємо.
 */
@Mixin(AbstractSelectionList.class)
public abstract class MixinAbstractSelectionListManiac {

    @Inject(method = "renderSelection", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$noOutline(GuiGraphics g, int top, int width, int height,
                                     int outer, int inner, CallbackInfo ci) {
        if (ManiacMenuTheme.isThemedList(this)) ci.cancel();
    }
}
