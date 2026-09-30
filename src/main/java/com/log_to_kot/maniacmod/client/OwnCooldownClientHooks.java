package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.items.BatItem;
import com.log_to_kot.maniacmod.items.CrowbarItem;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Звук «готово» для власних перезарядок предметів ({@code OwnCooldowns}).
 * Саму анімацію малює {@code ItemHudDecorator}; звук — тут, бо
 * декоратор викликається по разу на слот на кадр і не місце для звуку.
 *
 * <p>Звук лише коли перезарядка ДОТІКАЛА САМА (запис лишається в
 * {@code ClientMatchState}) і гравець досі має цей предмет; примусове
 * скидання між матчами прибирає запис — тоді тиша.</p>
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class OwnCooldownClientHooks {

    /** Предмети з власною перезарядкою: ключ → клас предмета (для перевірки «гравець його ще має»). */
    private static final String[] KEYS = { BatItem.COOLDOWN_KEY, CrowbarItem.COOLDOWN_KEY };
    private static final Class<?>[] ITEMS = { BatItem.class, CrowbarItem.class };
    private static final boolean[] wasActive = new boolean[KEYS.length];

    private OwnCooldownClientHooks() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            java.util.Arrays.fill(wasActive, false);
            return;
        }

        for (int i = 0; i < KEYS.length; i++) {
            String id = AbilityCooldownPacket.itemId(KEYS[i]);
            long readyAt = ClientMatchState.abilityReadyAtTick(id);
            boolean active = readyAt >= 0 && mc.level.getGameTime() < readyAt;

            if (wasActive[i] && !active && readyAt >= 0 && holds(mc, ITEMS[i])) {
                UiAnim.playReady();
            }
            wasActive[i] = active;
        }
    }

    private static boolean holds(Minecraft mc, Class<?> itemClass) {
        for (ItemStack stack : mc.player.getInventory().items) {
            if (itemClass.isInstance(stack.getItem())) return true;
        }
        return itemClass.isInstance(mc.player.getOffhandItem().getItem());
    }
}
