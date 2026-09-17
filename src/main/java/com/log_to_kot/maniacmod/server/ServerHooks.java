package com.log_to_kot.maniacmod.server;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.config.MapPointConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.maniacs.ManiacCombatModule;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.matchstate.PhaseSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * Міст між подіями Forge і матчем.
 *
 * ── Чому цей клас такий короткий ─────────────────────────────────────
 * v3 ServerEventHandler на 162 рядки робив усе одразу: перевіряв стан
 * гри, тікав пастки, синхронізував кулдаун щотік, перевіряв ремонт,
 * перевіряв втечу, міняв хітбокс. Кожна нова механіка дописувала туди
 * ще кілька рядків — і файл ставав другим центром логіки поряд із
 * ManiacGameManager.
 *
 * Тут лишається лише переадресація. Механіки реагують на фази у своїх
 * PhaseListener-ах і в цей файл не заглядають.
 */
public final class ServerHooks {

    /** Раз на секунду перевіряємо, чи не змінився конфіг на диску. */
    private static final int CONFIG_CHECK_INTERVAL_TICKS = 20;

    private int tickCounter = 0;

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ManiacCommand.register(event.getDispatcher());
    }

    /**
     * Один рядок замість шести ручних тіків. Перевірки фази теж немає:
     * PhaseManager сам вирішує, кого будити.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // Автопідхоплення конфігу йде незалежно від матчу: адмін має
        // змогу правити yml і в лобі, і посеред гри.
        if (++tickCounter >= CONFIG_CHECK_INTERVAL_TICKS) {
            tickCounter = 0;
            ManiacConfigs.tickWatcher();
            if (MapPointConfigs.tickWatcher()) {
                MatchOrchestrator current = ManiacMod.match();
                if (current != null) current.reloadConfiguredMap();
            }
        }

        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        match.tick(event.getServer().getPlayerList().getPlayers());
    }

    /**
     * Удар ЛКМ. Ванільна подія скасовується завжди, коли б'є маньяк:
     * шкоду рахує модуль удару за дальністю архетипу, а не ванільний
     * розрахунок за довжиною руки гравця.
     */
    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer attacker)) return;

        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        if (!match.isManiac(attacker.getUUID())) return;

        event.setCanceled(true);
        if (!ManiacCombatModule.damageAllowed()) return;

        match.combat().onAttack(attacker, event.getTarget());
    }

    /**
     * Синхронізація стану при вході.
     *
     * v3 цього не робив узагалі: гравець, що перезайшов посеред матчу,
     * лишався з порожнім клієнтським станом — без HUD, без ролі, і з
     * оверлеями від попередньої гри.
     */
    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        ModNetwork.toPlayer(player, new PhaseSyncPacket(match.phases().current()));
        ModNetwork.toPlayer(player, roleOf(match, player));
        match.inventoryAllocation().applyOnJoin(player);
    }

    /**
     * Вихід гравця. Сам матч вирішує, чи це вибуття, чи тимчасова
     * відсутність — тут лише повідомлення.
     */
    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        match.onPlayerLeft(player);
    }

    /**
     * Сутність зі старого матчу може бути в чанку, який завантажиться вже
     * після старту сервера. Persistent-маркер ловить її тут і не дає їй
     * повернутися на карту.
     */
    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel)) return;
        if (!com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry
                .isRegistered(event.getEntity())) return;

        MatchOrchestrator match = ManiacMod.match();
        if (match == null || match.phases().is(com.log_to_kot.maniacmod.core.phase.GamePhase.LOBBY)) {
            event.setCanceled(true);
            event.getEntity().discard();
        }
    }

    /**
     * Падіння з висоти. SurvivorModule сам вирішує, чи висота достатня
     * (fallKnockdownHeightBlocks) і чи ламається нога (legBreakChance);
     * тут лише переадресація й скасування ванільного урону від
     * падіння, коли модуль підтвердив, що обробив його сам.
     *
     * Окремо від DamageInterceptorRegistry: LivingFallEvent — не
     * LivingHurtEvent, тому загальна "ванільний урон вимкнено на весь
     * матч" гвардія його не ловить — цей хук закриває саме цю прогалину.
     */
    @SubscribeEvent
    public void onLivingFall(net.minecraftforge.event.entity.living.LivingFallEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        boolean handled = match.survivors().onFall(player, event.getDistance());
        if (handled) event.setCanceled(true);
    }

    // TODO(міграція maniacs): EntityEvent.Size → хітбокс і висота очей
    //   з архетипу маньяка (v3 ServerEventHandler.onEntitySize).

    // ── Допоміжне ────────────────────────────────────────────────────────

    private static RoleSyncPacket roleOf(MatchOrchestrator match, ServerPlayer player) {
        if (match.isManiac(player.getUUID())) {
            var archetype = match.maniacArchetype();
            return new RoleSyncPacket(RoleSyncPacket.Role.MANIAC,
                archetype == null ? "" : archetype.id());
        }
        if (match.isSurvivor(player.getUUID())) {
            var role = match.survivorRoleOf(player.getUUID());
            return new RoleSyncPacket(RoleSyncPacket.Role.SURVIVOR,
                role == null ? "" : role.id());
        }
        return new RoleSyncPacket(RoleSyncPacket.Role.SPECTATOR, "");
    }

    /** Розсилає фазу всім — викликається матчем при кожному переході. */
    public static void broadcastPhase(List<ServerPlayer> players,
                                      com.log_to_kot.maniacmod.core.phase.GamePhase phase) {
        ModNetwork.toPlayers(players, new PhaseSyncPacket(phase));
    }
}
