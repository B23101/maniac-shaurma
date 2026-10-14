package com.log_to_kot.maniacmod.mixin.browse;

import com.log_to_kot.maniacmod.client.screen.browse.ManiacBrowseStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Картка сервера.
 *
 * ── Чому TAIL, а не HEAD+cancel ──────────────────────────────────────
 * Ванільний render і запускає пінг (ServerStatusPinger), і вантажить іконку. Якщо скасувати
 * його на HEAD, пінг не піде зовсім. Тож ванільний render виконується повністю, а потім ми
 * перемальовуємо праву частину картки (від іконки вправо) поверх — з тих самих полів ServerData.
 * Іконка (32 px) та її ванільні зони кліків (приєднатись / вгору / вниз) лишаються як є.
 */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class MixinOnlineServerEntryBrowse {

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void maniacmod$card(GuiGraphics g, int index, int top, int left, int width, int height,
                                int mx, int my, boolean hovering, float pt, CallbackInfo ci) {
        if (!ManiacBrowseStyle.isStyled()) return;
        ServerSelectionList.OnlineServerEntry self = (ServerSelectionList.OnlineServerEntry) (Object) this;
        ManiacBrowseStyle.serverCard(g, self.getServerData(), index, left, top, width, height, hovering);
    }
}
