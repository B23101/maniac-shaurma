package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.screen.browse.ManiacBrowseStyle;
import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Фон екранів серверів/світів/форм — той самий, що в головному меню, замість «ґрунту».
 *
 * {@code JoinMultiplayerScreen}, {@code SelectWorldScreen} та інші самі
 * {@code renderBackground} не перевизначають, тому влучити в них міксином
 * не можна — вішаємось на базовий {@code Screen} і відсіюємо за типом екрана
 * ({@link ManiacMenuTheme#modeOf}). Для чужих екранів нічого не змінюється.
 * Ґрунт малюють два шляхи — у світі градієнтом, поза світом ґрунтом, — тож
 * перехоплюємо обидва.
 */
@Mixin(Screen.class)
public abstract class MixinScreenManiacBackdrop {

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$backdrop(GuiGraphics g, CallbackInfo ci) {
        draw(g, ci);
    }

    @Inject(method = "renderDirtBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$dirt(GuiGraphics g, CallbackInfo ci) {
        draw(g, ci);
    }

    private void draw(GuiGraphics g, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        ManiacMenuTheme.Mode mode = ManiacMenuTheme.modeOf(self);
        if (mode == null) {
            // Проміжні ванільні екрани входу/виходу (ProgressScreen, GenericDirtMessageScreen):
            // нашу сцену на них малюють інші міксини лише коли йде «сесія» завантаження, а в
            // першому кадрі її ще нема — звідси секундний ванільний ґрунт без тексту.
            // Тому ґрунт на них завжди замінюємо фоном мода; текст (якщо є) малює ваніль.
            if (self instanceof ProgressScreen || self instanceof GenericDirtMessageScreen) {
                ManiacBrowseStyle.renderBackdrop(g, self);
                ci.cancel();
            }
            return;
        }
        ManiacMenuTheme.renderBackdrop(g, self.width, self.height, mode);
        ci.cancel();
    }
}
