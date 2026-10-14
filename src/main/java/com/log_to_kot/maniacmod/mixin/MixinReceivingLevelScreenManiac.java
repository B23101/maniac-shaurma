package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.screen.loading.ManiacLoadingRenderer;
import com.log_to_kot.maniacmod.client.screen.loading.ManiacLoadingSession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Екран «Завантаження місцевості…» — остання ланка заходу (і зміни виміру).
 *
 * ── Утримання ────────────────────────────────────────────────────────
 * Ванільний {@code tick} закриває екран, щойно чанк під гравцем скомпільовано
 * — на швидкому з'єднанні це частка секунди, і вся анімація заходу була б
 * лише спалахом. Поки {@link ManiacLoadingSession#shouldHold()} каже «ще
 * рано», {@code tick} скасовується. Утримання діє лише на справжній захід
 * і має жорстку межу ({@code MIN_HOLD_MS}) — зміна виміру посеред матчу
 * не гальмується, а 30-секундний запобіжник ваніли лишається в силі.
 *
 * require = 0 — на випадок розбіжності мапінгу: тоді просто ванільний екран.
 */
@Mixin(ReceivingLevelScreen.class)
public abstract class MixinReceivingLevelScreenManiac extends Screen {

    protected MixinReceivingLevelScreenManiac(Component title) {
        super(title);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$render(GuiGraphics g, int mx, int my, float partialTick, CallbackInfo ci) {
        if (ManiacLoadingRenderer.renderScreen(g, this, ManiacLoadingSession.Kind.TERRAIN, mx, my)) {
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$hold(CallbackInfo ci) {
        if (ManiacLoadingSession.shouldHold()) ci.cancel();
    }
}
