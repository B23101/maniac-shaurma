package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.items.BatItem;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Звук «готово» для власних перезарядок предметів ({@code OwnCooldowns}).
 * Саму анімацію малює {@code ItemCooldownBarDecorator}; звук — тут, бо
 * декоратор викликається по разу на слот на кадр і не місце для звуку.
 *
 * <p>Звук лише коли перезарядка ДОТІКАЛА САМА (запис лишається в
 * {@code ClientMatchState}) і гравець досі має цей предмет; примусове
 * скидання між матчами прибирає запис — тоді тиша.</p>
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class OwnCooldownClientHooks {

    private static boolean batWasActive = false;

    private OwnCooldownClientHooks() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            batWasActive = false;
            return;
        }

        String id = AbilityCooldownPacket.itemId(BatItem.COOLDOWN_KEY);
        long readyAt = ClientMatchState.abilityReadyAtTick(id);
        boolean active = readyAt >= 0 && mc.level.getGameTime() < readyAt;

        if (batWasActive && !active && readyAt >= 0 && holdsBat(mc)) {
            UiAnim.playReady();
        }
        batWasActive = active;
    }

    private static boolean holdsBat(Minecraft mc) {
        for (ItemStack stack : mc.player.getInventory().items) {
            if (stack.getItem() instanceof BatItem) return true;
        }
        return mc.player.getOffhandItem().getItem() instanceof BatItem;
    }
}
