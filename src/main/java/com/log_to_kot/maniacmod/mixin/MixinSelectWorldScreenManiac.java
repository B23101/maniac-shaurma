package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Екран «Одиночна гра»: заголовок і список — як на екрані серверів; пошук у рядку заголовка. */
@Mixin(SelectWorldScreen.class)
public abstract class MixinSelectWorldScreenManiac extends Screen {

    protected MixinSelectWorldScreenManiac(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void maniacmod$layout(CallbackInfo ci) {
        WorldSelectionList list = ManiacMenuTheme.firstChild(this, WorldSelectionList.class);
        if (list == null) return;
        // той самий верх списку, що й на екрані серверів
        list.updateSize(this.width, this.height, ManiacMenuTheme.LIST_TOP,
            this.height - ManiacMenuTheme.LIST_BOTTOM_MARGIN);
        // пошук — в одному рядку із заголовком, праворуч; не займає місця над списком
        EditBox search = ManiacMenuTheme.firstChild(this, EditBox.class);
        if (search != null) {
            int sw = Math.min(150, list.getRowWidth() / 2);
            ManiacMenuTheme.place(search, list.getRowLeft() + list.getRowWidth() - sw,
                ManiacMenuTheme.HEADER_TOP + 14, sw);
        }
    }

    /**
     * Ванільний {@code SelectWorldScreen.render} НЕ викликає {@code renderBackground} — фон там
     * малював сам список («ґрунт»), а ми його вимкнули. Без цього екран нічого не стирає, і
     * знизу лишається завмерлий кадр головного меню. Малюємо той самий фон, що й у серверів.
     */
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void maniacmod$backdrop(GuiGraphics g, int mx, int my, float pt, CallbackInfo ci) {
        ManiacMenuTheme.renderBackdrop(g, this.width, this.height, ManiacMenuTheme.Mode.LIST);
    }

    @Redirect(method = "render",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"),
        require = 0)
    private void maniacmod$header(GuiGraphics g, Font font, Component title, int x, int y, int color) {
        WorldSelectionList list = ManiacMenuTheme.firstChild(this, WorldSelectionList.class);
        int left = list != null ? list.getRowLeft() : this.width / 2 - 135;
        ManiacMenuTheme.renderHeader(g, font, left, ManiacMenuTheme.HEADER_TOP,
            "maniacmod.menu.worlds.overline", "maniacmod.menu.worlds.title", 2.2f);
    }

    /**
     * Поле пошуку в стилі мода. Ванільне — чорний прямокутник із білою рамкою; рамку (кільце на
     * 1 px назовні від поля) перефарбовуємо, текст і курсор лишаються ванільними. Поки поле
     * порожнє й у фокусі, ванільна підказка зникає — тож малюємо її самі.
     */
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void maniacmod$searchSkin(GuiGraphics g, int mx, int my, float pt, CallbackInfo ci) {
        EditBox search = ManiacMenuTheme.firstChild(this, EditBox.class);
        if (search == null || !search.visible) return;
        ManiacMenuTheme.frameEditBox(g, search);
        if (search.getValue().isEmpty() && search.isFocused()) {
            Font f = this.font;
            g.drawString(f, I18n.get("maniacmod.menu.worlds.search"),
                search.getX() + 4, search.getY() + (search.getHeight() - 8) / 2,
                ManiacMenuTheme.DIM, false);
        }
    }
}
