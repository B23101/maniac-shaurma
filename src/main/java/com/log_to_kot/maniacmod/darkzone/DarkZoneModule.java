package com.log_to_kot.maniacmod.darkzone;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.DarkZoneConfigs;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.map.zones.DarkZonePolygon;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.darkzone.DarkZoneStatePacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * "Темна зона" — щотіку перевіряє, чи виживий стоїть усередині ХОЧ
 * ОДНОГО з розмічених {@link DarkZonePolygon} ({@link DarkZoneConfigs}),
 * і синхронізує клієнту булевий стан {@link DarkZoneStatePacket}.
 * <p>
 * Лише ВИЖИВІ отримують ефект (дизайн: "коли виживший заходить в неї
 * його екран стає темнішим") — маньяк бачить нормально, це його
 * інструмент для полювання в темній зоні, а не завада йому самому.
 * <p>
 * ── Чому пакет шлеться лише при ЗМІНІ стану ──────────────────────────
 * {@code DarkZoneEffect} на клієнті й так плавно інтерполює intensity
 * сам (не за кожен тік мережею) — повторний {@code active=true} щотіку,
 * поки гравець стоїть у зоні, нічого не додав би, лише зайвий трафік.
 * Пам'ятаємо останній надісланий стан на гравця ({@link #lastActive})
 * і шлемо пакет тільки на межі "зайшов"/"вийшов".
 */
public final class DarkZoneModule implements PhaseListener {

    private final Set<UUID> lastActive = new HashSet<>();

    @Override
    public String id() {
        return "darkzone";
    }

    @Override
    public void onPhaseExit(GamePhase phase, List<ServerPlayer> players) {
        // Кінець ігрової фази — усі, хто мав активний ефект, повертаються
        // до нормального освітлення. Без цього гравець, що вийшов із
        // зони рівно в момент завершення матчу, лишився б затемненим
        // до наступного разу, коли він фізично перетне межу зони.
        if (lastActive.isEmpty()) return;
        for (ServerPlayer player : players) {
            if (lastActive.remove(player.getUUID())) {
                ModNetwork.toPlayer(player, offPacket());
            }
        }
        lastActive.clear();
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        List<DarkZonePolygon> zones = DarkZoneConfigs.snapshot();
        if (zones.isEmpty() && lastActive.isEmpty()) return;

        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        for (ServerPlayer player : players) {
            if (!match.isSurvivor(player.getUUID())) continue;

            boolean inside = isInAnyZone(player, zones);
            boolean wasInside = lastActive.contains(player.getUUID());
            if (inside == wasInside) continue;

            if (inside) {
                lastActive.add(player.getUUID());
                ModNetwork.toPlayer(player, onPacket());
            } else {
                lastActive.remove(player.getUUID());
                ModNetwork.toPlayer(player, offPacket());
            }
        }
    }

    private static boolean isInAnyZone(ServerPlayer player, List<DarkZonePolygon> zones) {
        for (DarkZonePolygon zone : zones) {
            if (zone.contains(player)) return true;
        }
        return false;
    }

    private static DarkZoneStatePacket onPacket() {
        return new DarkZoneStatePacket(true,
            ManiacConfigs.get(ConfigSchema.DARKZONE_INTENSITY).floatValue(),
            ManiacConfigs.get(ConfigSchema.DARKZONE_TRANSITION_TICKS));
    }

    private static DarkZoneStatePacket offPacket() {
        return new DarkZoneStatePacket(false,
            ManiacConfigs.get(ConfigSchema.DARKZONE_INTENSITY).floatValue(),
            ManiacConfigs.get(ConfigSchema.DARKZONE_TRANSITION_TICKS));
    }
}
