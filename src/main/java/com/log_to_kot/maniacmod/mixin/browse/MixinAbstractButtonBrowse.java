package com.log_to_kot.maniacmod.mixin.browse;

import com.log_to_kot.maniacmod.client.screen.browse.ManiacBrowseStyle;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Кнопки-«леза» на стилізованих екранах. Клік, фокус, звук, активність — ванільні;
 * підміняється лише малювання. Підкласи з власним renderWidget (Checkbox, ImageButton…)
 * цей інжект не зачіпає.
 */
@Mixin(AbstractButton.class)
public abstract class MixinAbstractButtonBrowse {

    @Unique private float maniacmod$hover;
    @Unique private long maniacmod$last;

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$renderBlade(GuiGraphics g, int mx, int my, float pt, CallbackInfo ci) {
        if (!ManiacBrowseStyle.isStyled()) return;
        AbstractButton self = (AbstractButton) (Object) this;
        long now = Util.getMillis();
        float dt = maniacmod$last == 0L ? 0f : Math.min(0.1f, (now - maniacmod$last) / 1000f);
        maniacmod$last = now;
        maniacmod$hover = UiAnim.approach(maniacmod$hover,
            self.isHoveredOrFocused() && self.active ? 1f : 0f, 16f, dt);
        ManiacBrowseStyle.drawButton(g, self.getX(), self.getY(), self.getWidth(), self.getHeight(),
            self.getMessage(), self.active, maniacmod$hover, now);
        ci.cancel();
    }
}
