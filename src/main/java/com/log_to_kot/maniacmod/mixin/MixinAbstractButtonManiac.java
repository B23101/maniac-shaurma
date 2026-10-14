package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Кнопки-«леза» на екранах серверів/світів/форм. Міняється лише малювання —
 * {@code active}, фокус, колбеки й клік лишаються ванільними, тому жоден
 * ванільний {@code button.active = …} не ламається. На чужих екранах — ваніль.
 */
@Mixin(AbstractButton.class)
public abstract class MixinAbstractButtonManiac {

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$skin(GuiGraphics g, int mx, int my, float pt, CallbackInfo ci) {
        if (ManiacMenuTheme.drawButton(g, (AbstractButton) (Object) this)) ci.cancel();
    }
}
