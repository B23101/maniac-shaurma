package com.log_to_kot.maniacmod.survivors;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import net.minecraft.world.entity.player.Player;

/**
 * Чи гравець зараз лежить непритомним — джерело правди для ПОЗИ.
 *
 * Поза ({@code Pose.SWIMMING}, хітбокс 0.6×0.6 — «в одному блоці») ставиться
 * міксином {@code MixinPlayerDownedPose} і на СЕРВЕРІ, і на КЛІЄНТІ, бо
 * ванільний {@code Player.updatePlayerPose} виконується на обох і без цього
 * клієнт щотіка перезаписував би серверну позу «стоячою».
 *
 * ── Звідки береться відповідь ────────────────────────────────────────
 *   • сервер — {@code MatchContext} (стан {@code UNCONSCIOUS});
 *   • клієнт — список із {@code DownedSurvivorsPacket}
 *     ({@link ClientMatchState#isDowned}); клієнт не знає стану чужих
 *     гравців із жодного іншого джерела.
 *
 * Гілка клієнта викликається лише при {@code isClientSide}, тому на
 * виділеному сервері {@code ClientMatchState} не завантажується.
 */
public final class DownedPose {

    private DownedPose() {}

    public static boolean isDowned(Player player) {
        if (player.level().isClientSide) {
            return ClientMatchState.isDowned(player.getUUID());
        }
        MatchOrchestrator match = ManiacMod.match();
        return match != null && match.survivorStateOf(player.getUUID()) == SurvivorState.UNCONSCIOUS;
    }
}
