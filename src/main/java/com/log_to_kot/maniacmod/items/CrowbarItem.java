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
 * ── "Жива" анімація удару ────────────────────────────────────────────
 * {@code GeoItem}-контролер нижче лишається лише для вигляду лома на
 * землі/в GUI/у 3rd-person поза моментом удару (те саме розділення, що
 * в {@link MedkitItem} — див. його клас-докстрінг щодо причини). Сам
 * УДАР веде тіло гравця через {@link LiveHeldItemAction#playHit},
 * викликаний з {@code TrapModule.onCrowbarHit} одразу після
 * зарахування удару. На відміну від аптечки — це НЕ
 * {@link ItemArchetype#onUse} (лом б'є ЛКМ, а не ПКМ), тому власного
 * {@code beginServerTimed} тут не досить: {@code TrapModule} сам знає
 * момент зарахування удару й сам викликає {@link #playHitLive}.
 * <p>
 * Рух гравця під час удару НЕ блокується (lockMovement=false) — за
 * дизайном виживий може відступити одразу після замаху, лом б'є
 * миттєво; блокуються лише перемикання слоту й скидання лома на
 * коротку тривалість анімації удару, щоб {@code crowbarHitWearPercent}
 * не списався з предмета, якого гравець встиг викинути посеред кадру
 * (ефект видно, стека вже нема — той самий клас багів, що й у
 * TrapModule#onCrowbarHit докстрінгу вище щодо "хто платить за удар").
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

    /**
     * Тривалість playerlib-анімації удару в тіках — МУСИТЬ збігатись із
     * довжиною {@code crowbar_hit.json} (PlayerAnimator-формат). Коротка
     * навмисно: удар по капкану — різкий одноразовий рух, не дія
     * "утримання" (порівняй із {@link MedkitItem#USE_DURATION_TICKS}).
     */
    private static final int HIT_DURATION_TICKS = 12; // 0.6с при 20 tps

    private final AnimatableInstanceCache geoCache = new SingletonAnimatableInstanceCache(this);

    public CrowbarItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        // Спільний хелпер: один ліниво створений рендерер, шляхи — з VISUALS.
        // Свого класу-рендерера й класу-моделі лом більше не має.
        // renderInHand=false: у 1st/3rd-person руці геометрію лома
        // взагалі не малює GeckoLib — той шлях веде playerlib через
        // кістку rightItem під час "живої" пози (див. LiveHeldItemAction
        // і ItemGeoRenderer щодо ItemDisplayContext-фільтра).
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

    // ── "Жива" анімація удару ───────────────────────────────────────────

    /**
     * Викликати з {@code TrapModule.onCrowbarHit} ОДРАЗУ ПІСЛЯ
     * зарахування удару (дальність/кулдаун/міцність уже перевірені —
     * той самий момент, де метод грає звук і списує знос). Сервер
     * блокує слот/дроп лома на {@link #HIT_DURATION_TICKS}; клієнтський
     * тригер позы шле окремим пакетом {@code ItemAnimPacket}-аналогом
     * (той самий, яким раніше йшов GeckoLib-тригер) з боку
     * {@code ServerHooks}/{@code TrapPlacementController} — див. TODO
     * нижче щодо мережевого дроту.
     */
    public static void playHitLive(net.minecraft.server.level.ServerPlayer hitter) {
        LiveHeldItemAction.beginServerTimed(hitter, /* lockMovement */ false, HIT_DURATION_TICKS);
        // TODO(мережа): надіслати hitter-у й усім спостерігачам у радіусі
        // видимості сигнал відтворити PoseAction "maniacmod:crowbar_hit"
        // на клієнті — аналог ItemAnimPacket, але для playerlib замість
        // GeckoLib-тригера. До появи окремого PosePacket можна тимчасово
        // повторно використати ItemAnimPacket з новим ім'ям анімації і
        // на клієнті в ItemAnimPacket.Handler викликати
        // LiveHeldItemAction.beginClient("maniacmod:crowbar_hit") замість
        // GeoItem.triggerAnim(...).
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
