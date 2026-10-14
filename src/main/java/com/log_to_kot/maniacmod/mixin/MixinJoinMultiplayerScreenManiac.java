package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Екран «Мережева гра»: заголовок у стилі мода й список під ним.
 * Ванільні кнопки, пінг і колбеки не чіпаємо — кнопки лише отримують новий вигляд
 * через {@code MixinAbstractButtonManiac}.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class MixinJoinMultiplayerScreenManiac extends Screen {

    protected MixinJoinMultiplayerScreenManiac(Component title) {
        super(title);
    }

    /** Опускаємо список під наш заголовок (і при кожному resize — init викликається знову). */
    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void maniacmod$layout(CallbackInfo ci) {
        ServerSelectionList list = ManiacMenuTheme.firstChild(this, ServerSelectionList.class);
        if (list != null) {
            list.updateSize(this.width, this.height, ManiacMenuTheme.LIST_TOP,
                this.height - ManiacMenuTheme.LIST_BOTTOM_MARGIN);
        }
    }

    /** Ванільний центрований заголовок замінюємо на свій; решта {@code render} — ванільна. */
    @Redirect(method = "render",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"),
        require = 0)
    private void maniacmod$header(GuiGraphics g, Font font, Component title, int x, int y, int color) {
        ServerSelectionList list = ManiacMenuTheme.firstChild(this, ServerSelectionList.class);
        int left = list != null ? list.getRowLeft() : this.width / 2 - 150;
        ManiacMenuTheme.renderHeader(g, font, left, ManiacMenuTheme.HEADER_TOP,
            "maniacmod.menu.servers.overline", "maniacmod.menu.servers.title", 2.2f);
    }
}
