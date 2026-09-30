package com.log_to_kot.maniacmod.items;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;

import java.util.function.Consumer;

/**
 * Біта — предмет виживих для оглушення маньяка.
 *
 * ── Чим відрізняється від лома ({@link CrowbarItem}) ──────────────────
 * Той самий стан оглушення ({@code ManiacStunModule}), але:
 * <ul>
 *   <li>оглушує ТРОХИ ДОВШЕ ({@code batStunDurationTicks}, 12 с проти 8 с);</li>
 *   <li>коштує 50% міцності за удар ({@code batStunHitWearPercent}) — два
 *       удари й вона ламається;</li>
 *   <li>по капканах НЕ працює (це інструмент лома);</li>
 *   <li>перезарядка ВЛАСНА, а не ванільна {@code ItemCooldowns}: сервер
 *       веде її в {@link OwnCooldowns}, клієнт малює візуальний бар під
 *       іконкою предмета ({@code ItemHudDecorator}).</li>
 * </ul>
 *
 * ── Що тут, а що в ManiacStunModule ──────────────────────────────────
 * Тут лише ДАНІ предмета (міцність у NBT) і geo-вигляд — той самий підхід,
 * що в лома. Сам удар (ЛКМ по маньяку) обробляє
 * {@code ManiacStunModule#onBatHitManiac}, а хук ЛКМ у {@code ServerHooks}
 * лише переадресовує. Правий клік нічого не робить.
 *
 * ── Міцність ─────────────────────────────────────────────────────────
 * 100% на старті, тег {@link #DURABILITY_TAG}. Не ванільна Damageable — з
 * тієї ж причини, що в лома: потрібне число «50%» на іконці й власне
 * рішення «зламана біта зникає з руки».
 */
public class BatItem extends Item implements GeoItem {

    /** Id перезарядки: {@code OwnCooldowns} на сервері, {@code AbilityCooldownPacket.itemId} на клієнті. */
    public static final String COOLDOWN_KEY = "bat";

    /** Ключ NBT: залишок міцності, 0..100. */
    public static final String DURABILITY_TAG = "BatDurability";

    public static final int MAX_DURABILITY = 100;

    /** Вигляд предмета: шляхи за id (див. {@link ItemVisuals}). */
    private static final ItemVisuals VISUALS = ItemVisuals.of("bat");

    private static final String CONTROLLER_NAME = "batController";
    private static final RawAnimation ANIM_IDLE =
        RawAnimation.begin().thenLoop(VISUALS.animationSet().idle());

    private final AnimatableInstanceCache geoCache = new SingletonAnimatableInstanceCache(this);

    public BatItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        com.log_to_kot.maniacmod.client.renderer.ItemGeoRenderer
            .attach(consumer, () -> new com.log_to_kot.maniacmod.client.renderer.ItemGeoRenderer<>(VISUALS));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, CONTROLLER_NAME, 0,
                state -> state.setAndContinue(ANIM_IDLE)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // ── Міцність ─────────────────────────────────────────────────────────

    /** Залишок міцності 0..100. Немає тегу — нова біта, 100%. */
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

    /** Зламана біта (0%) не діє. */
    public static boolean isBroken(ItemStack stack) {
        return getDurability(stack) <= 0;
    }

    /**
     * Списує міцність за один удар по маньяку.
     *
     * @return залишок після удару (0..100)
     */
    public static int wear(ItemStack stack, int costPercent) {
        int next = Math.max(0, getDurability(stack) - costPercent);
        setDurability(stack, next);
        return next;
    }

    /**
     * Біта дійшла до 0% — ламається повністю й зникає з руки (ванільний
     * звук і уламки). Викликати одразу після {@link #wear}, лише для стека
     * в головній руці — так само, як {@link CrowbarItem#breakIfWornOut}.
     *
     * @return true, якщо біту зламано й видалено
     */
    public static boolean breakIfWornOut(net.minecraft.server.level.ServerPlayer owner, ItemStack stack) {
        if (!isBroken(stack)) return false;
        owner.broadcastBreakEvent(net.minecraft.world.InteractionHand.MAIN_HAND);
        owner.level().playSound(null, owner.blockPosition(),
            net.minecraft.sounds.SoundEvents.ITEM_BREAK, net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 0.8f);
        stack.shrink(1);
        return true;
    }
}
