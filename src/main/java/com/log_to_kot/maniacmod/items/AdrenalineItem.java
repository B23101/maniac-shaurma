package com.log_to_kot.maniacmod.items;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;

/**
 * Адреналін: на {@code adrenalineDurationTicks} (дефолт 10 с) виживий
 * отримує швидкість, а стаміна не витрачається. Коли дія закінчується —
 * «відкат»: стаміна стає 0 і {@code adrenalineCrashTicks} (дефолт 5 с)
 * не відновлюється.
 *
 * ── Що тут, а що в SurvivorModule ───────────────────────────────────
 * Предмет лише перевіряє, чи можна його вжити, і витрачає стек. Увесь
 * стан (таймери, правила StaminaService, ефект швидкості) веде
 * {@code SurvivorModule#onAdrenalineApplied} — той самий поділ, що між
 * {@link SplintItem} і {@code SurvivorModule#onSplintApplied}.
 *
 * Одне використання за матч. Не діє при поламаній нозі (нею керує Шина)
 * і на непритомного; повторно, поки адреналін ще діє чи триває відкат,
 * вжити не можна — предмет тоді не витрачається.
 */
public class AdrenalineItem extends ItemArchetype {

    public AdrenalineItem() {
        super(new Properties().stacksTo(1), /* cooldownTicks */ 0, /* maxUsesPerGame */ 1);
    }

    @Override
    protected InteractionResultHolder<ItemStack> onUse(ServerPlayer player, ItemStack stack) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return InteractionResultHolder.fail(stack);
        if (!match.phases().allows(PhaseRule.ITEM_USE)) return InteractionResultHolder.fail(stack);
        if (!match.isSurvivor(player.getUUID())) return InteractionResultHolder.fail(stack);

        if (!match.survivors().onAdrenalineApplied(player)) {
            return InteractionResultHolder.fail(stack);
        }

        stack.shrink(1);
        return InteractionResultHolder.success(stack);
    }
}
