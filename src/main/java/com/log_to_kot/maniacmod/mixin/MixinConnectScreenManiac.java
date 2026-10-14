package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.screen.loading.ManiacLoadingRenderer;
import com.log_to_kot.maniacmod.client.screen.loading.ManiacLoadingSession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Екран «Підключення до сервера…» малює сцена мода ({@code client/screen/loading}).
 *
 * ── Чому міксин, а не заміна екрана через {@code ScreenEvent.Opening} ─
 * {@code ConnectScreen} — не просто картинка: статичний {@code startConnecting}
 * створює його, запускає потік підключення і передає йому саме ЦЕЙ екземпляр
 * (статус, кнопка «Скасувати», {@code connection}). Підміна екрана на свій
 * лишила б потік оновлювати невидимий екран. Тому логіку підключення не
 * чіпаємо — перехоплюємо лише малювання.
 *
 * ── Три інжекти ──────────────────────────────────────────────────────
 * • {@code render} — малює сцену й скасовує ванільне малювання; кнопку
 *   «Скасувати» перемальовує сама сцена, клік обробляє ванільний віджет;
 * • {@code init} — переносить цю кнопку у верхній правий кут (там вона не
 *   перетинається з нижньою панеллю прогресу);
 * • {@code updateStatus} — ловить ванільний статус «connect.*», з якого
 *   сцена знає крок (з'єднання / авторизація / вхід у світ). Без нього крок
 *   лишається першим, нічого не ламається.
 *
 * require = 0 скрізь — якщо мапінг розійдеться, гра просто покаже ванільний
 * екран, а не впаде. Відомий компроміс: без ванільного {@code render} не
 * озвучується narrator, тому сесія сама повторює «narrator.joining» раз на 2 с.
 */
@Mixin(ConnectScreen.class)
public abstract class MixinConnectScreenManiac extends Screen {

    protected MixinConnectScreenManiac(Component title) {
        super(title);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$render(GuiGraphics g, int mx, int my, float partialTick, CallbackInfo ci) {
        if (ManiacLoadingRenderer.renderScreen(g, this, ManiacLoadingSession.Kind.CONNECT, mx, my)) {
            ci.cancel();
        }
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void maniacmod$placeCancel(CallbackInfo ci) {
        ManiacLoadingRenderer.placeCancel(this);
    }

    @Inject(method = "updateStatus", at = @At("HEAD"), require = 0)
    private void maniacmod$status(Component status, CallbackInfo ci) {
        ManiacLoadingSession.onVanillaStatus(status);
    }
}
