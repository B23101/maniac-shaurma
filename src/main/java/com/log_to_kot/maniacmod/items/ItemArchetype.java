package com.log_to_kot.maniacmod.items;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Базовий контракт для всіх предметів гри (Ліхтар, Лом, Біта, Шина,
 * Викрутка, Бензин, Ножиці, Адреналін, Аптечка...).
 *
 * НАВІЩО extends Item, а не окремий data-клас (як ManiacArchetype):
 * Forge-предмет ФІЗИЧНО мусить бути net.minecraft.world.item.Item
 * (реєструється через ModItems.ITEMS.register("id", XxxItem::new)) —
 * тому архетип тут одночасно і "статистична картка", і реальний
 * Item-клас. Підклас (MedkitItem, CrowbarItem...) перевизначає
 * лише onUse(...) — конкретний ефект; кулдаун і лічильник
 * використань рахує цей клас один раз.
 *
 * v3-еквівалент: кожен предмет у items/ItemImplementations.java
 * був окремим class XxxItem extends Item з дубльованою вручну (або
 * взагалі відсутньою) перевіркою кулдауну/ліміту використань.
 */
public abstract class ItemArchetype extends Item {

    /** Кулдаун між використаннями, в тіках. 0 = без кулдауну. */
    private final int cooldownTicks;

    /** Макс. використань за гру. -1 = необмежено (напр. Ліхтар). */
    private final int maxUsesPerGame;

    private final Map<UUID, Long> lastUsedTick = new HashMap<>();
    private final Map<UUID, Integer> usesSoFar = new HashMap<>();

    protected ItemArchetype(Properties properties, int cooldownTicks, int maxUsesPerGame) {
        super(properties);
        this.cooldownTicks = cooldownTicks;
        this.maxUsesPerGame = maxUsesPerGame;
    }

    /** Скидається на початку кожної нової гри (викликає ManiacGameManager.resetGame()). */
    public void resetUsageTracking() {
        lastUsedTick.clear();
        usesSoFar.clear();
    }

    @Override
    public final InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) return InteractionResultHolder.pass(player.getItemInHand(hand));
        if (!(player instanceof ServerPlayer sp)) return InteractionResultHolder.fail(player.getItemInHand(hand));

        if (!cooldownReady(sp) || !usesRemaining(sp)) {
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }

        InteractionResultHolder<ItemStack> result = onUse(sp, player.getItemInHand(hand));
        if (result.getResult().consumesAction()) {
            markUsed(sp);
        }
        return result;
    }

    /** Конкретний ефект предмета — підклас реалізує тільки це. */
    protected abstract InteractionResultHolder<ItemStack> onUse(ServerPlayer player, ItemStack stack);

    // ── Спільна логіка кулдауну/лічильника (ОДНА реалізація для всіх предметів) ──

    private boolean cooldownReady(ServerPlayer sp) {
        if (cooldownTicks <= 0) return true;
        Long last = lastUsedTick.get(sp.getUUID());
        return last == null || sp.getServer().getTickCount() - last >= cooldownTicks;
    }

    private boolean usesRemaining(ServerPlayer sp) {
        if (maxUsesPerGame < 0) return true;
        return usesSoFar.getOrDefault(sp.getUUID(), 0) < maxUsesPerGame;
    }

    private void markUsed(ServerPlayer sp) {
        lastUsedTick.put(sp.getUUID(), (long) sp.getServer().getTickCount());
        usesSoFar.merge(sp.getUUID(), 1, Integer::sum);
    }
}
