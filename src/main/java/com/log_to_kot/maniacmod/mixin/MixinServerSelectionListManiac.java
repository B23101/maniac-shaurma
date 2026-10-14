package com.log_to_kot.maniacmod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Список серверів без ванільних «ґрунтових» смуг зверху/знизу й підкладки —
 * під ним тепер наш фон. Це виклик двох ванільних сеттерів у конструкторі;
 * логіка списку (пінг, LAN, скрол) не змінюється.
 */
@Mixin(ServerSelectionList.class)
public abstract class MixinServerSelectionListManiac extends ObjectSelectionList<ServerSelectionList.Entry> {

    protected MixinServerSelectionListManiac(Minecraft mc, int w, int h, int y0, int y1, int itemH) {
        super(mc, w, h, y0, y1, itemH);
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void maniacmod$flat(JoinMultiplayerScreen screen, Minecraft mc, int w, int h,
                                int y0, int y1, int itemH, CallbackInfo ci) {
        this.setRenderBackground(false);
        this.setRenderTopAndBottom(false);
    }
}
