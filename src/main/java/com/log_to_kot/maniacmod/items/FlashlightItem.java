package com.log_to_kot.maniacmod.items;

import net.minecraft.world.item.Item;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;

import java.util.function.Consumer;

/**
 * Ліхтарик — предмет виживого з README ("Бачити в темних зонах, без
 * обмежень"). На відміну від Аптечки/Шини (одноразовий ефект по кліку),
 * ліхтарик діє, ПОКИ гравець його тримає в руці — клік ПКМ йому за
 * дизайном не потрібен, тому це не {@link ItemArchetype} (той клас
 * заточений під разове {@code onUse}), а простий {@code Item} із
 * geo-моделлю, той самий мінімальний підхід, що {@link FuelCanisterItem}
 * (див. клас-докстрінг щодо "Мінімальний GeoItem, без AnimatedGeoItem
 * бібліотеки" — той самий аргумент тут: ліхтарик тримається звичайно, у
 * руку гравця нічого не підміняється, тож {@code ArmOverride}
 * (AnimatedGeoItem) не потрібен).
 *
 * ── Хто веде фактичний ефект (світло + зняття затемнення) ────────────
 * Сам клас — лише "паспорт" предмета (geo-вигляд + маркер
 * {@link #isFlashlight}). Логіка "тримає/не тримає, отже вмикати
 * світло/вимикати темну зону" — у клієнтському тіковому хендлері
 * {@code com.log_to_kot.maniacmod.client.FlashlightClientHandler}, за
 * тим самим принципом, що {@code GeneratorFuelClientSoundHandler}
 * (клас-докстрінг: щотік перевіряє критерій "чи в руці потрібний
 * предмет" і сам вмикає/вимикає ефект — жодного стану в самому
 * предметі). Так само немає окремого серверного {@code onUse}: сервер
 * геть не знає про ліхтарик, ефект суто клієнтський і суто
 * косметичний (не змінює факт "гравець у темній зоні" для геймплею
 * маньяка — той усе одно бачить нормально, див.
 * {@code DarkZoneModule} клас-докстрінг).
 *
 * ── "Рука на рівні плечей" ────────────────────────────────────────────
 * Це NOT геометрія предмета (як {@code ArmOverride}-зброя маньяків), а
 * playerlib-поза тіла гравця — той самий шар
 * {@link LiveHeldItemAction}/{@code PoseLayerId.ITEM_ACTION}, яким уже
 * керує {@link MedkitItem} для свого use-кліпу, тільки тут не
 * одноразова (`registerUsePose`), а ПОСТІЙНА, "поки в руці"-поза
 * (`registerIdlePose`, `PoseSource.hold` — клас-докстрінг
 * {@link LiveHeldItemAction} прямо наводить приклад "піднести ліхтарик
 * до обличчя" щодо цього самого механізму). Сама поза — файл
 * {@code assets/maniacmod/player_animations/flashlight_idle.json}
 * (PlayerAnimator-формат): кут {@code rightArm} — 1:1 перенесений з
 * NotEnoughAnimations {@code LookAtItemAnimation.apply}, гілка
 * {@code HoldUpTarget.NONE} (звичайне тримання предмета "на видноті",
 * не наведення на камеру): {@code pitch = -holdUpItemOffset - lerp(...)},
 * {@code yaw = -0.2f}, {@code roll = 0.3f} — при дефолтному
 * {@code holdUpItemOffset=0} і гравці, що дивиться прямо вперед, це
 * {@code pitch≈-1.25 рад, yaw≈-0.2 рад, roll≈0.3 рад}, у градусах
 * {@code (-71.62, -11.46, 17.19)} (та сама конвенція осей — 0,0,0 у
 * NEA і в PAL це та сама ванільна поза опущеної руки, тому кути
 * переносяться без перерахунку знаку/порядку). Невелике циклічне
 * "дихання" руки (0.5с) поверх цього базового кута, {@code rightItem}
 * зсунутий і трохи нахилений (щоб сам ліхтарик помітно виступав
 * уперед з кулака, а не лишався в ванільній позиції) — художник може
 * замінити цей файл на детальнішу анімацію (хитання під час ходьби
 * тощо) пізніше, код тут міняти не доведеться.
 *
 * ── Geo-модель самого ліхтарика ───────────────────────────────────────
 * {@code flashlight.geo.json} — корпус ліхтарика, який playerlib
 * підставляє в кістку {@code rightItem} тієї самої pose-анімації (той
 * самий шлях позиціонування предмета в руці, що описаний у
 * {@link LiveHeldItemAction} клас-докстрінгу, пункт "positioning самого
 * предмета в руці читає HeldItemMixin... з кісток rightItem/leftItem").
 * Ідентична модель також малюється на землі/в GUI/в руці спостерігача
 * через {@link com.log_to_kot.maniacmod.client.renderer.ItemGeoRenderer}
 * — одна геометрія на всі контексти.
 */
public class FlashlightItem extends Item implements GeoItem {

    /** Вигляд предмета: шляхи за id (див. {@link ItemVisuals}). */
    private static final ItemVisuals VISUALS = ItemVisuals.of("flashlight");

    private static final String CONTROLLER_NAME = "flashlightController";
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("idle");

    /**
     * Id предмета для {@link LiveHeldItemAction#registerIdlePose(String)}
     * ({@code "flashlight"} — БЕЗ префікса й без {@code "_idle"}, той
     * самий "сирий" id, що приймає сам {@code registerIdlePose}).
     */
    public static final String HOLD_POSE_ITEM_ID = "flashlight";

    /**
     * Повне ім'я {@code PoseAction} тримання — конвенція
     * {@code modid:itemId_idle}, та сама, яку будує
     * {@link LiveHeldItemAction#registerIdlePose(String)} усередині
     * себе. Рахується тут ОДИН раз, а не дублюється окремою
     * конкатенацією в {@code ClientSetup} і в клієнтському хендлері.
     */
    public static final String HOLD_POSE_ACTION_NAME =
        com.log_to_kot.maniacmod.ManiacMod.MOD_ID + ":" + HOLD_POSE_ITEM_ID + "_idle";

    private final AnimatableInstanceCache geoCache = new SingletonAnimatableInstanceCache(this);

    public FlashlightItem() {
        super(new Properties().stacksTo(1));
    }

    /** Чи це ліхтарик — щоб клієнтський хендлер розпізнавав предмет без {@code instanceof} по всьому коду. */
    public static boolean isFlashlight(net.minecraft.world.item.ItemStack stack) {
        return stack.getItem() instanceof FlashlightItem;
    }

    // ── Geo-рендер (той самий мінімальний шлях, що FuelCanisterItem) ────

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
}
