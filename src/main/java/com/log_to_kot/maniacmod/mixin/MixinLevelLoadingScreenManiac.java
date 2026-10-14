package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.screen.loading.ManiacLoadingRenderer;
import com.log_to_kot.maniacmod.client.screen.loading.ManiacLoadingSession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Одиночна гра: «Підготовка місця появи: N%». Малюємо власну сцену.
 *
 * Справжній відсоток ванільного лоадера сюди НЕ береться: для цього треба
 * тип його слухача прогресу, а шлях до нього між збірками мапінгів
 * відрізняється, і помилка в імпорті ламає всю збірку. Прогрес у цьому кроці —
 * оцінка за часом (див. {@code ManiacLoadingSession}).
 */
@Mixin(LevelLoadingScreen.class)
public abstract class MixinLevelLoadingScreenManiac extends Screen {

    protected MixinLevelLoadingScreenManiac(Component title) {
        super(title);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$render(GuiGraphics g, int mx, int my, float partialTick, CallbackInfo ci) {
        if (ManiacLoadingRenderer.renderScreen(g, this, ManiacLoadingSession.Kind.LEVEL_LOADING, mx, my)) {
            ci.cancel();
        }
    }
}
