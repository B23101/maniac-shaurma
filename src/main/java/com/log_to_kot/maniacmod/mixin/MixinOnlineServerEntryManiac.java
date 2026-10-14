package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuCards;
import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Картка сервера. Ін'єкція в КІНЦІ ванільного {@code render}, без {@code cancellable}:
 * пінг стартує всередині ванільного {@code render} рядка, тож скасувати його на вході —
 * значить назавжди вимкнути пінг. Ванільний рядок відпрацьовує повністю (пінг, іконка,
 * тултіпи), а ми зверху перемальовуємо вміст з уже заповнених {@code ServerData}.
 */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class MixinOnlineServerEntryManiac {

    /** Справжнє значення «курсор на рядку» — ванільний render далі отримає false. */
    @Unique private boolean maniacmod$hover;
    @Unique private int maniacmod$mx;
    @Unique private int maniacmod$my;

    /**
     * Ванільна «кнопка заходу» (біла стрілка на іконці) малюється самим render, коли hovering == true.
     * Подаємо ванільному render false — накладку малюємо самі в ManiacMenuCards. Зони кліків
     * (зайти / вгору / вниз) у ванілі від цього не залежать і працюють як раніше.
     * Пінг, іконка й тултіпи (їхні умови — за координатами миші) теж не зачіпаються.
     */
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, require = 0)
    private boolean maniacmod$swallowHover(boolean hovering) {
        maniacmod$hover = hovering;
        return ManiacMenuTheme.listActive() ? false : hovering;
    }

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 5, require = 0)
    private int maniacmod$grabMx(int mx) {
        maniacmod$mx = mx;
        return mx;
    }

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 6, require = 0)
    private int maniacmod$grabMy(int my) {
        maniacmod$my = my;
        return my;
    }

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void maniacmod$card(GuiGraphics g, int index, int top, int left, int width, int height,
                                int mx, int my, boolean hovering, float pt, CallbackInfo ci) {
        if (!ManiacMenuTheme.listActive()) return;
        ServerSelectionList.OnlineServerEntry self = (ServerSelectionList.OnlineServerEntry) (Object) this;
        ManiacMenuCards.renderServer(g, self.getServerData(), top, left, width, height,
            maniacmod$hover, ManiacMenuTheme.isSelected(self), maniacmod$mx, maniacmod$my);
    }
}
