package com.log_to_kot.maniacmod.items;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Викрутка — інструмент виживих, що вдвічі пришвидшує ремонт генератора.
 *
 * ── Що тут, а що в GeneratorModule ──────────────────────────────────
 * Тут лише ДАНІ предмета: міцність у NBT і функції її читання/зношення —
 * той самий підхід, що в {@link CrowbarItem} і {@link BatItem}. Сама
 * механіка (множник прогресу й зношення) живе в
 * {@code GeneratorModule#tickOne}: викрутка не має {@code use()}, вона
 * діє пасивно, поки лежить у ГОЛОВНІЙ руці під час ремонту.
 *
 * ── Правила ──────────────────────────────────────────────────────────
 * <ul>
 *   <li>Бонус ({@code screwdriverSpeedBonus}, дефолт 1.0 → ×2) діє лише
 *       на стадії REPAIR і лише коли викрутка в головній руці.</li>
 *   <li>Міцність знімається ЛИШЕ за секунди реального ремонту
 *       ({@code screwdriverWearPercentPerSecond}, дефолт 5%/с → 20 с
 *       роботи на повну викрутку). Поки її просто тримають — не
 *       зношується.</li>
 *   <li>На 0% викрутка ламається й зникає з руки.</li>
 * </ul>
 *
 * Не ванільна Damageable — з тієї ж причини, що в лома: потрібне число
 * відсотків на іконці ({@code ItemHudDecorator}).
 */
public class ScrewdriverItem extends Item {

    /** Ключ NBT: залишок міцності, 0..100. */
    public static final String DURABILITY_TAG = "ScrewdriverDurability";

    public static final int MAX_DURABILITY = 100;

    public ScrewdriverItem() {
        super(new Properties().stacksTo(1));
    }

    /** Залишок міцності 0..100. Немає тегу — нова викрутка, 100%. */
    public static int getDurability(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(DURABILITY_TAG, net.minecraft.nbt.Tag.TAG_INT)) {
            return MAX_DURABILITY;
        }
        return Math.max(0, Math.min(MAX_DURABILITY, tag.getInt(DURABILITY_TAG)));
    }

    public static void setDurability(ItemStack stack, int percent) {
        stack.getOrCreateTag().putInt(DURABILITY_TAG, Math.max(0, Math.min(MAX_DURABILITY, percent)));
    }

    /** Зламана викрутка (0%) не дає бонусу. */
    public static boolean isBroken(ItemStack stack) {
        return getDurability(stack) <= 0;
    }

    /** Знімає {@code costPercent} міцності. @return залишок (0..100) */
    public static int wear(ItemStack stack, int costPercent) {
        int next = Math.max(0, getDurability(stack) - costPercent);
        setDurability(stack, next);
        return next;
    }

    /**
     * Викрутка дійшла до 0% — зникає з руки з ванільним звуком і
     * уламками (той самий ефект, що {@link CrowbarItem#breakIfWornOut}).
     * Викликати лише для стека з головної руки.
     *
     * @return true, якщо викрутку зламано й видалено
     */
    public static boolean breakIfWornOut(net.minecraft.server.level.ServerPlayer owner, ItemStack stack) {
        if (!isBroken(stack)) return false;
        owner.broadcastBreakEvent(net.minecraft.world.InteractionHand.MAIN_HAND);
        owner.level().playSound(null, owner.blockPosition(),
            net.minecraft.sounds.SoundEvents.ITEM_BREAK, net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 0.9f);
        stack.shrink(1);
        return true;
    }
}
