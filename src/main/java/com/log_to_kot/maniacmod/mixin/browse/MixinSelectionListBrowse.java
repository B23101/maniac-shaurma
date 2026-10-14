package com.log_to_kot.maniacmod.mixin.browse;

import com.log_to_kot.maniacmod.client.screen.browse.ManiacBrowseStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Загальний список (ObjectSelectionList → світи, сервери, моди). Сам список не замінюємо:
 *  • прибираємо «земляний» фон і верх/низ — крізь список видно фон меню;
 *  • рамка виділеного рядка — червона.
 */
@Mixin(AbstractSelectionList.class)
public abstract class MixinSelectionListBrowse {

    @Redirect(method = "render",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIFFIIII)V"),
        require = 0)
    private void maniacmod$noDirt(GuiGraphics g, ResourceLocation tex, int x, int y, float u, float v,
                                  int w, int h, int tw, int th) {
        if (ManiacBrowseStyle.hidesListDirt() && Screen.BACKGROUND_LOCATION.equals(tex)) return;
        g.blit(tex, x, y, u, v, w, h, tw, th);
    }

    @Inject(method = "renderSelection", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$selection(GuiGraphics g, int top, int width, int height, int outer, int inner,
                                     CallbackInfo ci) {
        if (!ManiacBrowseStyle.isStyled()) return;
        AbstractSelectionList<?> self = (AbstractSelectionList<?>) (Object) this;
        int x0 = self.getLeft() + (self.getRight() - self.getLeft() - width) / 2;
        ManiacBrowseStyle.selectionFrame(g, x0, x0 + width, top, height);
        ci.cancel();
    }
}
