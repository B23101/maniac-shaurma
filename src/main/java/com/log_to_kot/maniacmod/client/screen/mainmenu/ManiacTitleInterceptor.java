package com.log_to_kot.maniacmod.client.screen.mainmenu;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Підміняє ванільний {@link TitleScreen} на {@link ManiacTitleScreen}.
 *
 * ── Чому підміна в {@code ScreenEvent.Opening}, а не міксин ──────────
 * Усі шляхи «на головне меню» (старт гри, вихід зі світу, «Назад» зі списку
 * серверів, {@code ManiacEscMenuScreen.leaveGame}) створюють ванільний
 * {@code TitleScreen} і віддають його в {@code setScreen} — подія ловить їх усі,
 * а ванільний екран при цьому ніколи не стає поточним, тож не мигне на кадр.
 *
 * ── Що доводиться робити самим ───────────────────────────────────────
 * Ванільний {@code setScreen} при показі {@code TitleScreen} вимикає F3 і чистить
 * чат — але перевіряє це через {@code instanceof TitleScreen}, якому наш екран не
 * відповідає. Тому те саме робимо тут (повторне виконання нешкідливе).
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ManiacTitleInterceptor {

    private ManiacTitleInterceptor() {}

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof TitleScreen)) return;
        Minecraft mc = Minecraft.getInstance();
        mc.options.renderDebug = false;
        mc.gui.getChat().clearMessages(true);
        event.setNewScreen(new ManiacTitleScreen());
    }
}
