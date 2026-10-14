package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.screen.loading.ManiacLoadingRenderer;
import com.log_to_kot.maniacmod.client.screen.loading.ManiacLoadingSession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Проміжний ванільний екран GenericDirtMessageScreen між кроками заходу. Малюємо нашу сцену,
 * щоб між {@code ConnectScreen}/{@code ReceivingLevelScreen} не «проскакував»
 * ванільний фон. Умови — {@code ManiacLoadingSession.acceptsInterim}:
 * виходи зі світу («Збереження…») не чіпаємо.
 */
@Mixin(GenericDirtMessageScreen.class)
public abstract class MixinGenericDirtMessageScreenManiac extends Screen {

    protected MixinGenericDirtMessageScreenManiac(Component title) {
        super(title);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$render(GuiGraphics g, int mx, int my, float partialTick, CallbackInfo ci) {
        if (!ManiacLoadingSession.acceptsInterim(this)) return;
        if (ManiacLoadingRenderer.renderScreen(g, this, ManiacLoadingSession.Kind.INTERIM, mx, my)) {
            ci.cancel();
        }
    }
}
