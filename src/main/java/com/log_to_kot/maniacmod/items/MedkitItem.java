package com.log_to_kot.maniacmod.items;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;

/**
 * Аптечка: +30 хп, одне використання за матч.
 *
 * Зразковий предмет — за цією формою переносяться решта. Кулдаун і
 * ліміт використань не перевіряються тут: це робить ItemArchetype.
 * Предмет описує лише свій ефект.
 *
 * v3-еквівалент: ItemImplementations.MedkitItem, який працював із
 * системою «3 життя» (data.heal()). Тепер лікує хп у MatchContext.
 */
public class MedkitItem extends ItemArchetype {

    /** Скільки хп відновлює. За дизайном — 30. */
    private static final int HEAL_AMOUNT = 30;

    public MedkitItem() {
        super(new Properties().stacksTo(1), /* cooldownTicks */ 0, /* maxUsesPerGame */ 1);
    }

    @Override
    protected InteractionResultHolder<ItemStack> onUse(ServerPlayer player, ItemStack stack) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return InteractionResultHolder.fail(stack);
        if (!match.phases().allows(PhaseRule.ITEM_USE)) return InteractionResultHolder.fail(stack);

        // Нуль вилікуваних = хп уже повне. Предмет не витрачається —
        // інакше гравець втрачає аптечку через випадковий клік.
        int healed = match.healSurvivor(player.getUUID(), HEAL_AMOUNT);
        if (healed <= 0) return InteractionResultHolder.fail(stack);

        stack.shrink(1);
        return InteractionResultHolder.success(stack);
    }
}
