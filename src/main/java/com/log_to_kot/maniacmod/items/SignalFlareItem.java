package com.log_to_kot.maniacmod.items;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.evac.EvacModule;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.notify.ActionBarPacket;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;

/**
 * Сигнальна ракета. ПКМ у руці запускає її — але лише на галявині ({@code /maniac clearing}),
 * на землі, і лише після виклику вертольота з приймача.
 *
 * <p>Ракета списується тільки при УСПІШНОМУ запуску: помилка (не та зона, ще не викликали)
 * не коштує предмета. Уся логіка й повідомлення — в {@link EvacModule#tryFireFlare}; тут лише
 * переклад результату в текст і списання стека (однакова форма з рештою предметів).</p>
 */
public class SignalFlareItem extends ItemArchetype {

    public SignalFlareItem() {
        super(new Properties().stacksTo(1), /* cooldownTicks */ 20, /* maxUsesPerGame */ -1);
    }

    @Override
    protected InteractionResultHolder<ItemStack> onUse(ServerPlayer player, ItemStack stack) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return InteractionResultHolder.fail(stack);

        EvacModule.FlareResult result = match.evac().tryFireFlare(player);
        if (result == EvacModule.FlareResult.OK) {
            stack.shrink(1);
            return InteractionResultHolder.success(stack);
        }
        ModNetwork.toPlayer(player, new ActionBarPacket(ActionBarMessageType.ERROR,
            "maniacmod.evac.flare." + result.name().toLowerCase()));
        return InteractionResultHolder.fail(stack);
    }
}
