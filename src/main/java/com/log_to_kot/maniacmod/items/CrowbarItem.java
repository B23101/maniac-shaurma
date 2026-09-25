package com.log_to_kot.maniacmod.items;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
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
 * Лом — інструмент виживих для звільнення з капкана.
 *
 * ── Що тут, а що в TrapModule ───────────────────────────────────────
 * Це лише ДАНІ предмета: міцність у NBT і функції її читання/зношення,
 * той самий підхід, що заряд у {@link FuelCanisterItem}. Сам удар
 * (ЛКМ по капкану) обробляє {@code TrapModule#onCrowbarHit}, а хук
 * ЛКМ у {@code ServerHooks} лише переадресовує. Предмет не має
 * {@code use()}: правий клік ломом нічого не робить, бо за дизайном він
 * б'є ЛКМ.
 *
 * ── Міцність і перезарядка ───────────────────────────────────────────
 * 100% на старті; кожен удар по капкану знімає
 * {@code crowbarHitWearPercent} (33): три удари поспіль — і лом
 * ламається. Між ударами — перезарядка {@code crowbarCooldownTicks}
 * (20 с), яку ставить {@code TrapModule} через ванільний
 * {@code ItemCooldowns}: HUD ванілі малює її сам, окремого коду не треба.
 *
 * ── Чому НЕ ванільна міцність (Damageable) ───────────────────────────
 * Ванільна смужка міцності не показує відсотків і ламає предмет
 * «зникненням». Нам потрібно число «67%» на іконці (декоратор, як у
 * каністри) і рішення «зламаний лом — предмет лишається порожнім»
 * приймає TrapModule, а не ванільна механіка.
 */
public class CrowbarItem extends Item implements GeoItem {

    /** Ключ NBT: залишок міцності, 0..100. */
    public static final String DURABILITY_TAG = "CrowbarDurability";

    public static final int MAX_DURABILITY = 100;

    /** Вигляд предмета: шляхи за id (див. {@link ItemVisuals}). */
    private static final ItemVisuals VISUALS = ItemVisuals.of("crowbar");

    private static final String CONTROLLER_NAME = "crowbarController";
    private static final RawAnimation ANIM_IDLE =
        RawAnimation.begin().thenLoop(VISUALS.animationSet().idle());

    private final AnimatableInstanceCache geoCache = new SingletonAnimatableInstanceCache(this);

    public CrowbarItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        // Спільний хелпер: один ліниво створений рендерер, шляхи — з VISUALS.
        // Свого класу-рендерера й класу-моделі лом більше не має.
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

    /** Залишок міцності 0..100. Немає тегу — новий лом, 100%. */
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

    /** Зламаний лом (0%) не діє. */
    public static boolean isBroken(ItemStack stack) {
        return getDurability(stack) <= 0;
    }

    /**
     * Списує міцність за один удар по капкану ({@code crowbarHitWearPercent}).
     *
     * @return залишок після удару (0..100)
     */
    public static int wear(ItemStack stack) {
        return wear(stack, ManiacConfigs.get(ConfigSchema.CROWBAR_HIT_WEAR_PERCENT));
    }

    /**
     * Списує міцність за один удар на довільний відсоток. Використовує
     * {@code ManiacStunModule} для удару ПО МАНЬЯКУ —
     * {@code crowbarStunHitWearPercent} відрізняється від звичайного
     * зносу по капкану, тому відсоток приходить параметром, а не
     * читається тут із конфігу вдруге.
     *
     * @return залишок після удару (0..100)
     */
    public static int wear(ItemStack stack, int costPercent) {
        int next = Math.max(0, getDurability(stack) - costPercent);
        setDurability(stack, next);
        return next;
    }
}
