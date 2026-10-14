package com.log_to_kot.maniacmod.mixin.browse;

import com.log_to_kot.maniacmod.client.screen.browse.ManiacBrowseStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.server.LanServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Картка сервера локальної мережі: вся ширина рядка перемальовується поверх ванільної. */
@Mixin(ServerSelectionList.NetworkServerEntry.class)
public abstract class MixinLanServerEntryBrowse {

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void maniacmod$card(GuiGraphics g, int index, int top, int left, int width, int height,
                                int mx, int my, boolean hovering, float pt, CallbackInfo ci) {
        if (!ManiacBrowseStyle.isStyled()) return;
        ServerSelectionList.NetworkServerEntry self = (ServerSelectionList.NetworkServerEntry) (Object) this;
        LanServer lan = self.getServerData();
        ManiacBrowseStyle.lanCard(g, I18n.get("lanServer.title"), lan.getMotd(), lan.getAddress(),
            left, top, width, height, hovering);
    }
}
