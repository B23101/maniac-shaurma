package com.log_to_kot.maniacmod.mixin.browse;

import com.log_to_kot.maniacmod.client.screen.browse.ManiacBrowseStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Фон екранів вибору світу/сервера, форм сервера та списку модів — як у головному меню.
 * Ванільний renderBackground скасовується лише для екранів зі списку {@link ManiacBrowseStyle#isStyled}.
 * (ModListScreen не викликає renderBackground сам — це робить його ModListWidget; результат той самий.)
 */
@Mixin(Screen.class)
public abstract class MixinScreenBrowseBackground {

    @Inject(method = "renderBackground(Lnet/minecraft/client/gui/GuiGraphics;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$browseBackground(GuiGraphics g, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if (!ManiacBrowseStyle.isStyled(self)) return;
        ManiacBrowseStyle.renderBackdrop(g, self);
        ci.cancel();
    }
}
