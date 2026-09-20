package com.log_to_kot.maniacmod.items;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;

/**
 * Шина: лагодить поламану ногу (BROKEN_LEG → HEALTHY), одне
 * використання за матч (за таблицею items/README.md).
 *
 * ── Ефект ────────────────────────────────────────────────────────────
 * Поки нога поламана, {@code SurvivorModule.tickBrokenLegStamina}
 * щотік притискає стаміну гравця до нуля (див. клас-докстрінг того
 * методу). Єдиний спосіб зняти BROKEN_LEG — повернути стан у HEALTHY:
 * з наступного тіку tickBrokenLegStamina більше не спрацьовує (стан
 * уже не BROKEN_LEG), і стаміна відновлюється за звичайними правилами
 * StaminaRules (recoveryPerSecond/recoveryDelaySeconds).
 *
 * ── Чому не можна вжити при CRAWLING/UNCONSCIOUS ────────────────────
 * Поламана нога — це стан ПІСЛЯ вставання (onStandUpAttempt), не під
 * час лежання. Поки гравець CRAWLING чи UNCONSCIOUS, pendingLegBreak
 * ще не застосований і survivorStateOf(...) не поверне BROKEN_LEG —
 * onUse природно відмовить (fail), нічого спеціально перевіряти не
 * треба понад сам стан.
 *
 * v3-еквівалент: items/ItemImplementations.java не мав цього предмета
 * взагалі (TODO у README items/) — поламана нога в v3 не існувала як
 * окремий стан.
 */
public class SplintItem extends ItemArchetype {

    public SplintItem() {
        super(new Properties().stacksTo(1), /* cooldownTicks */ 0, /* maxUsesPerGame */ 1);
    }

    @Override
    protected InteractionResultHolder<ItemStack> onUse(ServerPlayer player, ItemStack stack) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return InteractionResultHolder.fail(stack);
        if (!match.phases().allows(PhaseRule.ITEM_USE)) return InteractionResultHolder.fail(stack);
        if (!match.isSurvivor(player.getUUID())) return InteractionResultHolder.fail(stack);
        if (match.survivorStateOf(player.getUUID()) != SurvivorState.BROKEN_LEG) {
            return InteractionResultHolder.fail(stack);
        }

        match.setSurvivorState(player.getUUID(), SurvivorState.HEALTHY);
        match.survivors().onSplintApplied(player);

        stack.shrink(1);
        return InteractionResultHolder.success(stack);
    }
}
