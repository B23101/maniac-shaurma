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
 * Каністра з бензином — предмет для стадії FUEL генератора (див.
 * {@link com.log_to_kot.maniacmod.map.zones.GeneratorPoi}).
 *
 * ── Постійна каністра з власним зарядом ──────────────────────────────
 * Каністра НІКОЛИ не списується й не зникає з інвентаря. Замість цього
 * кожен окремий {@link ItemStack} несе власний ЗАРЯД 0-100% у NBT самого
 * стека (ключ {@value #CHARGE_TAG}) — не в сесії ремонту на сервері.
 * Це принципово: сесія живе лише поки утримуються обидві клавіші
 * (Shift і ПКМ — два окремі {@code KeyMapping}, які фізично не
 * відпускаються в один тік), і будь-який розсинхрон закривав її разом
 * із лічильником, що в ній лежав. Заряд у самому предметі від цього не
 * залежить.
 *
 * Заливка йде 1 до 1: скільки відсотків додалось генератору за тік —
 * рівно стільки ж віднімається із заряду каністри в руці (див.
 * {@code GeneratorModule.tickFuel}). Коли заряд доходить до 0% — каністра
 * лишається в руці порожньою й просто перестає давати прогрес; гравець
 * бере іншу, заряджену.
 *
 * ── Звідки береться початковий заряд ─────────────────────────────────
 * Усі місця видачі створюють стек через {@code new ItemStack(item)} без
 * NBT ({@code GroundItemEntity.interact}, творча вкладка, {@code /give}).
 * Тому {@link #getCharge} повертає {@link #MAX_CHARGE}, коли тегу немає:
 * нова каністра автоматично повна, а окремої ініціалізації при видачі не
 * потрібно. Тег з'являється лише після першого списання.
 *
 * ── Число заряду на іконці ───────────────────────────────────────────
 * Малюється клієнтським {@code IItemDecorator}
 * ({@link com.log_to_kot.maniacmod.client.renderer.FuelCanisterChargeDecorator}),
 * який реєструється в {@code ClientSetup} і викликається з
 * {@code GuiGraphics.renderItemDecorations} — тобто працює і у ванільних
 * слотах, і в кастомному хотбарі мода.
 *
 * ── Чому НЕ ItemArchetype ────────────────────────────────────────────
 * {@link ItemArchetype} побудований під одноразовий клік ({@code onUse}
 * → {@code shrink(1)} миттєво) — саме так задумувались Аптечка й Шина.
 * Каністра ж заливає генератор так само, як лагодження заповнює
 * REPAIR: УТРИМАННЯМ Shift+ПКМ. Тому предмет — простий {@code Item} без
 * власної логіки взаємодії: усе утримання йде через той самий
 * {@code GeneratorRepairHoldPacket}, що й ремонт
 * ({@code ClientInputHandler.handleGeneratorRepair} не розрізняє
 * стадії — дивись докстрінг там), а {@code GeneratorModule} на сервері
 * сам перевіряє, чи в руці гравця саме непорожня каністра, перш ніж
 * рахувати прогрес заливу й списувати заряд.
 *
 * ── Чому GeoItem, а не звичайний Item із плоскою текстурою ────────────
 * За дизайном (і за проханням художника) каністра має власну просту
 * 3D-модель з анімацією "хлюпання" в руці, як генератор — той самий
 * підхід, що {@code GeneratorEntity}: GeckoLib-модель, поки що
 * ЗАГЛУШКА (див. {@code fuel_canister.geo.json} — простий короб з
 * ручкою), яку художник замінить фінальною геометрією без зміни
 * коду (лише збереже імена кісток).
 *
 * ── Мінімальний GeoItem, без AnimatedGeoItem бібліотеки ────────────────
 * {@code dev.shaurmalib.forge.item.AnimatedGeoItem} у shaurma-lib
 * розрахований на предмети, що ЗАМІЩУЮТЬ руку гравця скіном
 * (ArmOverride) — зброя маньяків. Каністра тримається звичайно, без
 * заміни руки, тому підключати весь той контракт (і реєструвати
 * ItemDefinition з ArmOverride, якого немає) зайве — тут прямий,
 * короткий {@link GeoItem} з одним петлевим контролером "idle", без
 * запуску конкретних анімацій самого предмета (сам процес заливу
 * показує {@code GeneratorProgressOverlay}, не анімація предмета).
 */
public class FuelCanisterItem extends Item implements GeoItem {

    /** Ключ NBT-тегу стека, де лежить заряд (int 0..100). */
    public static final String CHARGE_TAG = "FuelCharge";

    /** Повний заряд, %. Також значення за замовчуванням, коли тегу ще немає. */
    public static final int MAX_CHARGE = 100;

    private static final String CONTROLLER_NAME = "canisterController";
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("idle");

    private final AnimatableInstanceCache geoCache = new SingletonAnimatableInstanceCache(this);

    public FuelCanisterItem() {
        super(new Properties().stacksTo(1));
    }

    /**
     * Єдиний спосіб підключити рендерер до GeoItem у Forge-збірці GeckoLib 4.4.x.
     * {@code GeoItem.makeRenderer(...)} і {@code getRenderProvider()} існують
     * ЛИШЕ у Fabric-модулі GeckoLib — у Forge-модулі їх немає взагалі, і спроба
     * їх використати дає "cannot find symbol" / "does not override". Тут
     * рендерер віддається через стандартний Forge-хук
     * {@code Item.initializeClient(Consumer<IClientItemExtensions>)} —
     * саме цей метод викликає рушій, щоб дізнатись кастомний рендерер стека.
     * Виконується лише на клієнті (сам виклик гарантовано клієнтський),
     * рендерер створюється один раз, лениво,
     * через клас, який існує лише на {@code Dist.CLIENT} (окремий клас
     * нижче в {@code client.renderer}), щоб уникнути завантаження
     * клієнтських класів на сервері.
     */
    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private com.log_to_kot.maniacmod.client.renderer.FuelCanisterItemRenderer renderer;

            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new com.log_to_kot.maniacmod.client.renderer.FuelCanisterItemRenderer();
                }
                return renderer;
            }
        });
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

    // ── Заряд стека ──────────────────────────────────────────────────────

    /**
     * Заряд каністри в цьому стеку, 0-100%. Немає тегу (щойно видана з
     * лута, творчої вкладки чи {@code /give}) — вважається повною.
     * Значення з тегу підтискається в діапазон, щоб зіпсований NBT
     * (наприклад, ручне редагування) не дав від'ємного чи >100% заряду.
     */
    public static int getCharge(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(CHARGE_TAG, net.minecraft.nbt.Tag.TAG_INT)) {
            return MAX_CHARGE;
        }
        return Math.max(0, Math.min(MAX_CHARGE, tag.getInt(CHARGE_TAG)));
    }

    /** Записує заряд у NBT стека, затиснутий у [0, {@link #MAX_CHARGE}]. */
    public static void setCharge(ItemStack stack, int percent) {
        stack.getOrCreateTag().putInt(CHARGE_TAG, Math.max(0, Math.min(MAX_CHARGE, percent)));
    }

    /** Порожня каністра — лишається в інвентарі, але прогрес не дає. */
    public static boolean isEmpty(ItemStack stack) {
        return getCharge(stack) <= 0;
    }
}