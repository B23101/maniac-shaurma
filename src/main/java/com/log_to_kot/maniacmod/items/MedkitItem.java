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
 * ── Чому geo-предмет, а не ванільний спрайт ──────────────────────────
 * Аптечка — предмет, який гравець дістає в найнапруженіший момент, і за
 * дизайном вона має бути 3D-моделлю в руці з живою анімацією
 * використання (той самий шлях рендера, що в лома й каністри — див.
 * {@code ItemGeoRenderer}). Файли асе́тів беруться за id предмета:
 * {@code geo/item/medkit.geo.json}, {@code animations/item/medkit.animation.json},
 * {@code textures/item/medkit.png}.
 *
 * ── Анімації ─────────────────────────────────────────────────────────
 *   • {@code idle} — loop, грає весь час, поки предмет намальовано;
 *   • {@code use}  — ОДНОРАЗОВА, тригериться на власний правий клік
 *     ({@link ItemArchetype#onUseClient}). Один контролер на дві
 *     анімації: GeckoLib сам віддає перевагу тригернутій, доки вона не
 *     скінчиться, і повертається до {@code idle} — саме той перехід
 *     «idle → use → idle» без дьоргання, який потрібен, і жодного
 *     власного стану для цього тримати не треба.
 *
 * ── Хто бачить анімацію ──────────────────────────────────────────────
 * Тригер клієнтський, тож анімацію бачить ЛИШЕ той, хто користується
 * аптечкою (свій гравець). Для інших гравців модель лишається в {@code idle}:
 * щоб показати рух чужого використання, потрібен був би синхронізований
 * тригер через GeckoLib-мережу з id стека — наразі свідомо не робимо,
 * бо ефект (лікування) і так видно зі шкали здоров'я.
 *
 * ── Що лишається на сервері ──────────────────────────────────────────
 * Сам ефект, кулдаун і ліміт використань — у {@link ItemArchetype}
 * ({@code onUse}); предмет описує лише свій ефект.
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
     * Правий клік — граємо анімацію використання.
     *
     * Інстанс-id беремо ТИМ САМИМ методом, що й рендерер
     * ({@code GeoItem.getId}), тож тригер і малюнок завжди влучають в
     * один менеджер анімацій: якщо сервер колись призначить стеку
     * власний id ({@code getOrAssignId}), він уже приїде клієнту в NBT і
     * обидва місця прочитають його однаково.
     */
    @Override
    protected void onUseClient(Player player, ItemStack stack) {
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

        stack.shrink(1);
        return InteractionResultHolder.success(stack);
    }
}
