package com.log_to_kot.maniacmod.mixin.browse;

import com.log_to_kot.maniacmod.client.screen.browse.ManiacBrowseStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Перефарбовує рамку поля вводу ПІСЛЯ ванільного малювання (текст і курсор лишаються ванільними). */
@Mixin(EditBox.class)
public abstract class MixinEditBoxBrowse {

    @Inject(method = "renderWidget", at = @At("TAIL"), require = 0)
    private void maniacmod$frame(GuiGraphics g, int mx, int my, float pt, CallbackInfo ci) {
        if (!ManiacBrowseStyle.isStyled()) return;
        EditBox self = (EditBox) (Object) this;
        ManiacBrowseStyle.editBoxFrame(g, self.getX(), self.getY(), self.getWidth(), self.getHeight(), self.isFocused());
    }
}
