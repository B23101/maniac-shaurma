package com.log_to_kot.maniacmod.leaderboard;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.progression.profile.PlayerProfile;
import com.log_to_kot.maniacmod.progression.profile.PlayerProfileStorage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Події, що тримають топи свіжими: вхід/вихід оновлюють скін, нік і «востаннє в мережі» в профілі,
 * зупинка сервера скидає кеш. Окремий клас (як {@code ManiacRatingHooks}): топи від фази матчу не залежать.
 *
 * <p>Профіль без жодної активності не чіпаємо — новачок, що лише зайшов, не створює файл і не потрапляє в топи.</p>
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class LeaderboardHooks {

    private LeaderboardHooks() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) touch(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) touch(player);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LeaderboardService.clear();
    }

    private static void touch(ServerPlayer player) {
        PlayerProfile profile = PlayerProfileStorage.load(player.getUUID());
        if (!profile.hasActivity()) return;
        LeaderboardService.stamp(player, profile);
        PlayerProfileStorage.save(player.getUUID(), player.getGameProfile().getName(), profile);
    }
}
