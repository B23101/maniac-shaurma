package com.log_to_kot.maniacmod.items;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.renderer.ItemGeoRenderer;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
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
 * Аптечка: +30 хп, одне використання за матч.
 *
 * ── Два шари, запущені ОДНИМ викликом (як TACZ) ─────────────────────
 * TACZ (третьоособові анімації зброї) тримає тіло гравця й саму зброю
 * у ДВОХ незалежних форматах — playerAnimator-поза для руки/торса і
 * GeckoLib/Bedrock-модель для самої зброї — але НІКОЛИ не намагається
 * вмонтувати геометрію зброї у playerAnimator-файл (це технічно
 * неможливо: playerAnimator керує лише скелетом гравця, чужу геометрію
 * не малює). Замість цього {@code AnimationManager} у TACZ просто
 * запускає ОБИДВА шари з ОДНІЄЇ форжевої події (наприклад
 * {@code GunShootEvent}) — вони йдуть синхронно не тому, що фізично
 * зшиті, а тому що стартують в один тік з узгодженими тривалостями,
 * підготованими одним художником в один файл-сесію Blockbench.
 * <p>
 * Тут той самий принцип: {@link #onUseClient} — єдина точка, де
 * запускаються ОБИДВА шари одночасно:
 * <ol>
 *   <li>{@link LiveHeldItemAction#beginClient} — playerlib-поза
 *       {@link #LIVE_USE_POSE} на шарі {@code PoseLayerId.ITEM_ACTION}
 *       (див. {@link LiveHeldItemAction}) — керує РУКАМИ/ТОРСОМ/ГОЛОВОЮ
 *       гравця. Файл: {@code assets/maniacmod/player_animations/medkit_use.json}.</li>
 *   <li>{@code triggerAnim(...)} — GeckoLib {@code use}-кліп на
 *       контролері {@link #CONTROLLER} — керує САМОЮ моделлю аптечки
 *       (кришка відкривається, бинт з'являється). Файл:
 *       {@code assets/maniacmod/animations/item/medkit.animation.json}.</li>
 * </ol>
 * Обидва файли МУСЯТЬ мати однакову тривалість ({@link #USE_DURATION_TICKS})
 * і узгоджений темп руху — це відповідальність художника в Blockbench,
 * а не коду: код лише гарантує, що обидва тригеряться в той самий тік.
 *
 * ── Хто бачить анімацію ──────────────────────────────────────────────
 * {@link LiveHeldItemAction#beginClient} тригериться лише на клієнті
 * власника (прогноз кліку — див. {@link ItemArchetype#onUseClient}), але
 * саму playerlib-позу бачать УСІ спостерігачі автоматично, бо PAL
 * застосовує {@code AnimationStack} до будь-якого {@code AbstractClientPlayer},
 * що рендериться (див. {@code PlayerPoseController} клас-докстрінг,
 * "Третя особа працює автоматично"). GeckoLib {@code use}-тригер на
 * предметі синхронізується мережею окремо, як і раніше (звичайний
 * {@code GeoItem} тригер-протокол) — див. TODO нижче.
 *
 * ── Що лишається на сервері ──────────────────────────────────────────
 * Сам ефект, кулдаун і ліміт використань — у {@link ItemArchetype}
 * ({@code onUse}); блокування руху/слота/дропу на час анімації —
 * {@link LiveHeldItemAction#beginServerTimed}.
 */
public class MedkitItem extends ItemArchetype implements GeoItem {

    /** Скільки хп відновлює. За дизайном — 30. */
    private static final int HEAL_AMOUNT = 30;

    /** Вигляд предмета: шляхи за id (див. {@link ItemVisuals}). */
    private static final ItemVisuals VISUALS = ItemVisuals.of("medkit");

    /** Контролер, на якому тригериться {@code use}. */
    private static final String CONTROLLER = "medkit";

    private static final RawAnimation ANIM_IDLE =
        RawAnimation.begin().thenLoop(VISUALS.animationSet().idle());
    private static final RawAnimation ANIM_USE =
        RawAnimation.begin().thenPlay(VISUALS.animationSet().use());

    /** Один екземпляр кешу на предмет: id стеків усередині нього різні. */
    private final AnimatableInstanceCache geoCache = new SingletonAnimatableInstanceCache(this);

    public MedkitItem() {
        super(new Properties().stacksTo(1), /* cooldownTicks */ 0, /* maxUsesPerGame */ 1);
    }

    // ── Geo-рендер ───────────────────────────────────────────────────────

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        ItemGeoRenderer.attach(consumer, () -> new ItemGeoRenderer<>(VISUALS));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // transitionTickTime = 2: короткий зшив між idle і use, щоб
        // модель не «стрибала» в позу анімації за один кадр.
        controllers.add(new AnimationController<>(this, CONTROLLER, 2,
                state -> state.setAndContinue(ANIM_IDLE))
            .triggerableAnim(VISUALS.animationSet().use(), ANIM_USE));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    /**
     * Ім'я {@code PoseAction}, зареєстрованої в {@code ClientSetup} через
     * {@link LiveHeldItemAction#registerUsePose(String)}. Керує лише
     * РУКАМИ/ТОРСОМ гравця — див. клас-докстрінг щодо другого,
     * незалежного шару (GeckoLib use-кліп на самій моделі аптечки).
     */
    private static final String LIVE_USE_POSE = ManiacMod.MOD_ID + ":medkit_use";

    /**
     * Тривалість анімації бинтування в тіках — МУСИТЬ збігатись із
     * довжиною {@code medkit_use.json} (PlayerAnimator-файл, не
     * GeckoLib). Розсинхрон у той чи інший бік не ламає гру (лок все
     * одно знімається), але або передчасно розблоковує гравця (лишок
     * анімації додограє вже без блокувань), або тримає його зайвий час.
     */
    private static final int USE_DURATION_TICKS = 40; // 2 секунди при 20 tps

    @Override
    protected void onUseClient(Player player, ItemStack stack) {
        // Обидва шари стартують тут же, в один тік (принцип TACZ
        // AnimationManager: один event → playerlib-поза тіла +
        // GeckoLib-кліп предмета одночасно, без спільної геометрії).

        // 1) Тіло: руки/торс/голова виконують рух "піднести аптечку,
        // відкрити, дістати бинт" — сам предмет тут не малюється.
        LiveHeldItemAction.beginClient(LIVE_USE_POSE);

        // 2) Предмет: та сама модель аптечки, що й на землі/в GUI,
        // грає одноразовий use-кліп (кришка відкривається, бинт
        // з'являється) поверх ItemGeoRenderer — художник у Blockbench
        // підганяє її темп під medkit_use.json, щоб рух виглядав
        // синхронним, хоч фізично це два незалежні файли.
        triggerAnim(player, GeoItem.getId(stack), CONTROLLER, VISUALS.animationSet().use());
    }

    // ── Ефект ────────────────────────────────────────────────────────────

    @Override
    protected InteractionResultHolder<ItemStack> onUse(ServerPlayer player, ItemStack stack) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return InteractionResultHolder.fail(stack);
        if (!match.phases().allows(PhaseRule.ITEM_USE)) return InteractionResultHolder.fail(stack);

        // Нуль вилікуваних = хп уже повне. Предмет не витрачається —
        // інакше гравець втрачає аптечку через випадковий клік.
        int healed = match.healSurvivor(player.getUUID(), HEAL_AMOUNT);
        if (healed <= 0) return InteractionResultHolder.fail(stack);

        // Аптечка бинтується на місці — гравець не рухається, поки триває
        // анімація (USE_DURATION_TICKS має збігатись із довжиною файлу
        // medkit_use.json у тіках), не може перемкнутись на інший слот
        // чи викинути її (Q) посеред use. beginServerTimed сам планує
        // симетричний endServer — окремого тік-лічильника тут не треба.
        LiveHeldItemAction.beginServerTimed(player, /* lockMovement */ true, USE_DURATION_TICKS);

        stack.shrink(1);
        return InteractionResultHolder.success(stack);
    }
}
