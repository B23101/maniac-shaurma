package com.log_to_kot.maniacmod.events;

import com.log_to_kot.maniacmod.commands.ManiacCommand;
import com.log_to_kot.maniacmod.entity.ManiacType;
import com.log_to_kot.maniacmod.sound.ModSounds;
import com.log_to_kot.maniacmod.sound.SoundHelper;
import com.log_to_kot.maniacmod.game.ManiacGameManager;
import com.log_to_kot.maniacmod.network.AbilityCooldownPacket;
import com.log_to_kot.maniacmod.items.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

public class ServerEventHandler {

    private static int tickCounter = 0;


    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ManiacCommand.register(event.getDispatcher());
    }

    /**
     * Forge-native hitbox override — replaces broken Mixin approach.
     * Changes player AABB size so Chucky fits through 1-block gaps
     * and Slenderman is blocked by low ceilings (server-side).
     */
    @SubscribeEvent
    public void onEntitySize(EntityEvent.Size event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!ManiacGameManager.isManiac(player)) return;
        ManiacType type = ManiacGameManager.getManiacType(player.getUUID());
        if (type == null) return;

        EntityDimensions newSize = EntityDimensions.scalable(type.hitboxWidth, type.hitboxHeight);
        event.setNewSize(newSize);
        event.setNewEyeHeight(type.eyeHeight);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (ManiacGameManager.getGameState() != ManiacGameManager.GameState.RUNNING) return;

        List<ServerPlayer> players = event.getServer().getPlayerList().getPlayers();

        ManiacGameManager.tickAll(players);

        // Sync ability cooldown visual to maniac client every tick
        players.stream().filter(ManiacGameManager::isManiac).findFirst().ifPresent(maniac -> {
            int cd  = ManiacGameManager.getManiacAttackCooldown();
            int max = ManiacGameManager.getManiacMaxAttackCooldown();
            if (max > 0) {
                float fraction = (float) cd / max;
                com.log_to_kot.maniacmod.network.ModMessages.sendAbilityCooldown(maniac, fraction);
            }
        });

        var physics = ManiacGameManager.getManiacPhysics();
        if (physics != null) physics.tick();

        for (ServerPlayer p : players) {
            if (!ManiacGameManager.isSurvivor(p)) continue;
            // Ремонт тепер керується через ManiacGameManager.refreshRepair() з GeneratorBlock.use()
            // і тікається в ManiacGameManager.tickRepairSessions()
            if (p.getMainHandItem().getItem() == ModItems.SCREWDRIVER.get())
                ManiacGameManager.tickUnlock(p, p.blockPosition());
            ManiacGameManager.checkEscape(p);
        }

        tickCounter++;
        if (tickCounter < 20) return;
        tickCounter = 0;

        ManiacGameManager.tickAllSeconds(players);

        // Поповнити нескінченні предмети маньяка
        players.stream().filter(ManiacGameManager::isManiac).findFirst()
            .ifPresent(ManiacGameManager::refillManiacItems);

        int activeGens     = ManiacGameManager.countActiveGenerators();
        int totalSurvivors = ManiacGameManager.getSurvivorDataMap().size();
        int corpsesCount   = ManiacGameManager.getCorpses().size();

        ServerPlayer maniacPlayer = players.stream()
            .filter(ManiacGameManager::isManiac).findFirst().orElse(null);

        for (ServerPlayer p : players) {
            if (ManiacGameManager.isSurvivor(p)) {
                var data = ManiacGameManager.getSurvivorData(p.getUUID());
                String lives     = data != null ? data.livesBar() : "";
                String corpseStr = corpsesCount > 0 ? "  §6☠" + corpsesCount + " трупів" : "";
                // Знаходимо генератор що ремонтується поруч
                var nearGen = com.log_to_kot.maniacmod.game.ManiacGameManager.getGenerators().stream()
                    .filter(g -> !g.isActive() && g.getPos().closerThan(p.blockPosition(), 3))
                    .findFirst();
                String genInfo = nearGen.isPresent()
                    ? " §8[§e" + nearGen.get().getStageProgress() + "% §8етап §e"
                        + (nearGen.get().getStagesDone() + 1) + "§8/§e"
                        + nearGen.get().getStagesRequired() + "§8]"
                    : "";
                p.sendSystemMessage(Component.literal(
                    "§8[§7Ген: §a" + activeGens + "§7/§c5" + genInfo + "  " + lives + corpseStr));

                if (maniacPlayer != null && p.distanceTo(maniacPlayer) < 20.0f)
                    SoundHelper.playTo(p, ModSounds.MANIAC_NEARBY.get(), 0.6f, 1.0f);

            } else if (ManiacGameManager.isManiac(p)) {
                int atkCd  = ManiacGameManager.getManiacAttackCooldown();
                int bearCd = ManiacGameManager.getBearTrapCooldown();
                int wireCd = ManiacGameManager.getWireCooldown();
                int ropeCd = ManiacGameManager.getRopeCooldown();
                int mineCd = ManiacGameManager.getMineCooldown();
                String atkStr  = atkCd  > 0 ? "§c🔪" + (atkCd  / 20) + "с" : "§a🔪✅";
                String bearStr = bearCd > 0 ? " §6🪤"  + (bearCd / 20) + "с" : " §a🪤✅";
                String wireStr = wireCd > 0 ? " §e⚡"  + (wireCd / 20) + "с" : " §a⚡✅";
                String ropeStr = ropeCd > 0 ? " §6🪢"  + (ropeCd / 20) + "с" : " §a🪢✅";
                String mineStr = mineCd > 0 ? " §8💣"  + (mineCd / 20) + "с" : " §a💣✅";
                p.sendSystemMessage(Component.literal(
                    "§8[§7Виживаючих: §c" + totalSurvivors
                    + "  " + atkStr + bearStr + wireStr + ropeStr + mineStr));
                if (!p.hasEffect(MobEffects.MOVEMENT_SPEED))
                    ManiacGameManager.applyManiacEffects(p);
            }
        }

// Таймер завершення гри вимкнено
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (ManiacGameManager.getGameState() != ManiacGameManager.GameState.RUNNING) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (ManiacGameManager.isSurvivor(sp))
            sp.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, Integer.MAX_VALUE, 0, false, true));
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (ManiacGameManager.getGameState() != ManiacGameManager.GameState.RUNNING) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (ManiacGameManager.isManiac(sp)) {
            if (sp.getServer() != null)
                broadcast(sp.getServer().getPlayerList().getPlayers(), "§aМаньяк вийшов — виживаючі перемогли!");
            ManiacGameManager.resetGame();
        }
    }

    private static void broadcast(List<ServerPlayer> players, String msg) {
        players.forEach(p -> p.sendSystemMessage(Component.literal(msg)));
    }
}
