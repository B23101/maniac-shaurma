package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** «Пряме підключення»: та сама форма, що й «Додати сервер», з одним полем. */
@Mixin(DirectJoinServerScreen.class)
public abstract class MixinDirectJoinServerScreenManiac extends Screen {

    protected MixinDirectJoinServerScreenManiac(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void maniacmod$layout(CallbackInfo ci) {
        EditBox ip = ManiacMenuTheme.firstChild(this, EditBox.class);
        if (ip == null) return;
        ManiacMenuTheme.Form f = ManiacMenuTheme.layout(this.width, this.height, false, false);
        ManiacMenuTheme.place(ip, f.left(), f.addrY(), f.width());
        for (GuiEventListener c : this.children()) {
            if (c instanceof Button b) {
                ManiacMenuTheme.place(b, f.left(), ManiacMenuTheme.isPrimary((AbstractButton) b) ? f.doneY() : f.cancelY(), f.width());
            }
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$render(GuiGraphics g, int mx, int my, float pt, CallbackInfo ci) {
        EditBox ip = ManiacMenuTheme.firstChild(this, EditBox.class);
        if (ip == null) return;
        ManiacMenuTheme.Form f = ManiacMenuTheme.layout(this.width, this.height, false, false);
        ManiacMenuTheme.renderForm(this, g, mx, my, pt, f, "maniacmod.menu.servers.overline",
            "maniacmod.menu.server.direct.title", null, ip);
        ci.cancel();
    }
}
