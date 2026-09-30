package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.GroundItemEntity;
import com.log_to_kot.maniacmod.loot.GroundItemAim;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.intent.GroundItemPickupPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Підбір предмета ПКМ по променю погляду ({@link GroundItemAim}), а не по
 * ванільному прицілу.
 *
 * ── Що саме перехоплюється ────────────────────────────────────────────
 * Якщо ванільний приціл уже на предметі — нічого не робимо: ванілька сама
 * надішле взаємодію з сутністю ({@code GroundItemEntity#interact}). Якщо
 * приціл на іншій сутності (лежачий союзник, генератор) — теж не чіпаємо.
 * Перехоплюємо лише випадок, коли приціл «впирається» у блок без колізії
 * (трава, квіти), а за ним промінь погляду влучає в предмет: тоді ПКМ
 * скасовується (щоб не «використати» предмет у руці) і шлеться намір
 * {@link GroundItemPickupPacket}. Сервер усе перевіряє заново.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GroundItemAimHandler {

    private GroundItemAimHandler() {}

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        // Shift+ПКМ — ремонт генератора, не підбір (дзеркалить GroundItemEntity.interact).
        if (mc.player.isShiftKeyDown()) return;
        if (mc.hitResult instanceof EntityHitResult) return;

        GroundItemEntity item = GroundItemAim.find(mc.player, GroundItemAim.CLIENT_MARGIN);
        if (item == null) return;
        if (mc.player.distanceTo(item) > ClientMatchState.groundItemPickupRangeBlocks()) return;

        event.setCanceled(true);
        event.setSwingHand(false);
        ModNetwork.toServer(new GroundItemPickupPacket(item.getId()));
    }
}
