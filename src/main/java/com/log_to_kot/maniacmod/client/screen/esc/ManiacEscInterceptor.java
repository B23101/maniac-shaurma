package com.log_to_kot.maniacmod.client.screen.esc;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.screen.rating.ClientManiacRatingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Підміняє ванільне меню паузи на {@link ManiacEscMenuScreen}.
 *
 * ── Чому LOW ─────────────────────────────────────────────────────────
 * Інші обробники теж чіпають {@link PauseScreen}: розміщення пастки
 * ({@code TrapPlacementController}) скасовує його, щоб Esc виходив із
 * режиму розміщення, а кіно-вступ ({@code IntroClient}) скасовує усі
 * екрани. Скасована подія до обробників без {@code receiveCanceled}
 * не доходить, тож з пріоритетом LOW наша підміна спрацьовує лише тоді,
 * коли Esc справді мав би відкрити меню паузи.
 *
 * ── Умови підміни ────────────────────────────────────────────────────
 * Лише коли гравець у світі: без світу (головне меню) PauseScreen не
 * відкривається, а чужі виклики {@code new PauseScreen} поза грою
 * ми не чіпаємо.
 *
 * ── Вихід зі світу ───────────────────────────────────────────────────
 * Скидає «оцінити пізніше»: воно діє лише в межах однієї сесії.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ManiacEscInterceptor {

    private ManiacEscInterceptor() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof PauseScreen)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        event.setNewScreen(new ManiacEscMenuScreen());
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientManiacRatingState.get().onDisconnect();
        com.log_to_kot.maniacmod.client.ClientProfileState.clear();
        com.log_to_kot.maniacmod.client.history.ClientHistoryState.clear();
        com.log_to_kot.maniacmod.client.leaderboard.ClientLeaderboardState.clear();
    }
}
