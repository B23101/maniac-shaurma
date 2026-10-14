package com.log_to_kot.maniacmod.client.screen.profile;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * У лобі інвентаря нема, тож його клавіша (за замовчуванням E — береться з налаштувань керування)
 * відкриває профіль: ванільний екран інвентаря підміняється на {@link ProfileScreen}.
 *
 * <p>Лише у фазі {@code LOBBY} і лише для гравців у режимі виживання/пригоди: творчий режим і
 * спостерігач (адміни, що будують мапу) зберігають звичайний інвентар. Під час матчу нічого не
 * чіпаємо — там інвентар або потрібен виживому, або його нема в маніяка.</p>
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ProfileKeyInterceptor {

    private ProfileKeyInterceptor() {}

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof InventoryScreen
            || event.getNewScreen() instanceof CreativeModeInventoryScreen)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.player.isCreative() || mc.player.isSpectator()) return;
        if (ClientMatchState.phase() != GamePhase.LOBBY) return;
        event.setNewScreen(new ProfileScreen(null));
    }
}
