package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuCards;
import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Картка світу. Дані ({@code LevelSummary}) ванільний рядок уже зчитав окремо —
 * ми лише показуємо їх інакше, після ванільного {@code render}.
 */
@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class MixinWorldListEntryManiac {

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void maniacmod$card(GuiGraphics g, int index, int top, int left, int width, int height,
                                int mx, int my, boolean hovering, float pt, CallbackInfo ci) {
        if (!ManiacMenuTheme.listActive()) return;
        net.minecraft.world.level.storage.LevelSummary summary = ManiacMenuCards.summaryOf(this);
        ManiacMenuCards.renderWorld(g, summary, index, top, left, width, height,
            hovering, ManiacMenuTheme.isSelected(this));
        // замість білої ванільної стрілки — наша, як на іконці сервера. Заблоковані / несумісні
        // світи лишаємо ванільними: там ванільна накладка показує попередження й тултіпи.
        if (hovering && summary != null && !summary.isLocked() && !summary.isDisabled()) {
            ManiacMenuCards.worldIconOverlay(g, left, top, mx, my);
        }
    }
}
