package com.log_to_kot.maniacmod.server;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.barricade.BarricadeModule;
import com.log_to_kot.maniacmod.config.BarricadeConfigs;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.registry.ModBlocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge-хуки барикад: лише переадресація в {@link BarricadeModule}.
 *
 * <ul>
 *   <li>ПКМ по блоку/в повітря: режим будування адміна АБО відкриття міні-гри «витягни цвяхи» ломом виживим;</li>
 *   <li>зламати блок-дошки руками не можна (лише креатив, і тоді розмітка знімається);</li>
 *   <li>старт сервера — виставити розмітку у світ.</li>
 * </ul>
 */
public final class BarricadeHooks {

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MatchOrchestrator match = ManiacMod.match();
        if (match != null) match.barricades().restoreAll(event.getServer());
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        BarricadeModule barricades = match.barricades();

        // Режим будування: УСІ ПКМ адміна (обидві руки) — наші, ваніль (поставити блок) не потрібна.
        if (barricades.builder().isActive(player.getUUID())) {
            if (event.getHand() == InteractionHand.MAIN_HAND) {
                barricades.builder().onUse(player, event.getHitVec(), player.getMainHandItem());
            }
            cancel(event);
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        double reach = ManiacConfigs.get(ConfigSchema.BARRICADE_CROWBAR_RANGE_BLOCKS);
        var held = player.getMainHandItem();

        // Гнучка барикада може бути ПЕРЕД блоком, по якому клікнули (дошка на стіні).
        var flexible = barricades.findFlexible(player, reach);
        if (flexible != null) {
            if (barricades.onCrowbarUseOnFlexible(player, held, flexible.entity())) cancel(event);
            return;
        }

        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (state.is(ModBlocks.BOARDS.get()) && event.getLevel() instanceof ServerLevel level) {
            if (player.distanceToSqr(event.getPos().getCenter()) > (reach + 1.0) * (reach + 1.0)) return;
            if (barricades.onCrowbarUseOnFixed(player, held, level, event.getPos())) cancel(event);
        }
    }

    /** ПКМ у повітря (клік по блоку сюди не приходить): гнучка барикада або режим будування. */
    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        BarricadeModule barricades = match.barricades();

        if (barricades.builder().isActive(player.getUUID())) {
            cancel(event);   // нічого не будуємо в повітря, але й ваніль не треба
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        var flexible = barricades.findFlexible(player,
            ManiacConfigs.get(ConfigSchema.BARRICADE_CROWBAR_RANGE_BLOCKS));
        if (flexible != null
            && barricades.onCrowbarUseOnFlexible(player, player.getMainHandItem(), flexible.entity())) {
            cancel(event);
        }
    }

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent event) {
        if (!event.getState().is(ModBlocks.BOARDS.get())) return;
        ServerPlayer player = event.getPlayer() instanceof ServerPlayer p ? p : null;
        if (player == null || !player.isCreative()) {
            event.setCanceled(true);   // руками не зламати — лише лом у матчі
            return;
        }
        // Адмін у креативі прибрав блок навмисно — знімаємо й реєстрацію, щоб він не повертався.
        if (event.getLevel() instanceof ServerLevel level) {
            BarricadeConfigs.removeFixed(BarricadeModule.dimId(level), event.getPos());
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        match.barricades().builder().drop(event.getEntity().getUUID());
        match.barricades().onPlayerGone(event.getEntity().getUUID());
    }

    private static void cancel(PlayerInteractEvent event) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }
}
