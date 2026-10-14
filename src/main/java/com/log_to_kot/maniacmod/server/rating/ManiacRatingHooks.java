package com.log_to_kot.maniacmod.server.rating;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Події, що живлять оцінки маньяків: вхід гравця → синхронізація,
 * вихід → скидання ліміту частоти.
 *
 * ── Чому окремий клас, а не ServerHooks ──────────────────────────────
 * {@code ServerHooks.onPlayerJoin} одразу виходить, якщо матч ще не
 * піднятий. Оцінки від матчу не залежать: гравець має отримати свої
 * оцінки за будь-якої фази й навіть до першого старту.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ManiacRatingHooks {

    private ManiacRatingHooks() {}

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ManiacRatingService.sendSync(player);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        ManiacRatingService.forget(event.getEntity().getUUID());
    }
}
