package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.EditServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * «Додати / Редагувати сервер»: вигляд і розкладка форми переписані, логіка — ванільна.
 *
 * Поля, кнопки «Готово»/«Скасувати», перемикач ресурспаків, перевірка адреси
 * (кнопка «Готово» неактивна, поки адреса некоректна), колбек і запис у
 * {@code ServerData} — усе ванільне. Ми лише перекладаємо віджети в ліву колонку
 * ({@code init}) і малюємо їх самі ({@code render}), без приватних полів: віджети
 * беремо з {@code children()}.
 *
 * Add чи Edit визначаємо раз при відкритті: у «Додати» ванільний {@code ServerData}
 * має порожню адресу.
 */
@Mixin(EditServerScreen.class)
public abstract class MixinAddServerScreenManiac extends Screen {

    /** 0 — ще не визначено, 1 — додавання, 2 — редагування. */
    @Unique private int maniacmod$mode;

    protected MixinAddServerScreenManiac(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void maniacmod$layout(CallbackInfo ci) {
        List<EditBox> boxes = ManiacMenuTheme.children(this, EditBox.class);
        if (boxes.size() < 2) return;
        EditBox name = boxes.get(0);
        EditBox ip = boxes.get(1);
        if (maniacmod$mode == 0) maniacmod$mode = ip.getValue().isEmpty() ? 1 : 2;

        ManiacMenuTheme.Form f = ManiacMenuTheme.layout(this.width, this.height, true, true);
        ManiacMenuTheme.place(name, f.left(), f.nameY(), f.width());
        ManiacMenuTheme.place(ip, f.left(), f.addrY(), f.width());
        for (GuiEventListener c : this.children()) {
            if (c instanceof CycleButton<?> cb) {
                ManiacMenuTheme.place(cb, f.left(), f.cycleY(), f.width());
            } else if (c instanceof Button b) {
                ManiacMenuTheme.place(b, f.left(), ManiacMenuTheme.isPrimary((AbstractButton) b) ? f.doneY() : f.cancelY(), f.width());
            }
        }
    }

    /** Ванільний {@code render} лише малює — тут його повністю замінює наш. */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$render(GuiGraphics g, int mx, int my, float pt, CallbackInfo ci) {
        List<EditBox> boxes = ManiacMenuTheme.children(this, EditBox.class);
        if (boxes.size() < 2) return; // незнайома розкладка — лишаємо ванільний render
        ManiacMenuTheme.Form f = ManiacMenuTheme.layout(this.width, this.height, true, true);
        ManiacMenuTheme.renderForm(this, g, mx, my, pt, f, "maniacmod.menu.servers.overline",
            maniacmod$mode == 2 ? "maniacmod.menu.server.edit.title" : "maniacmod.menu.server.add.title",
            boxes.get(0), boxes.get(1));
        ci.cancel();
    }
}
