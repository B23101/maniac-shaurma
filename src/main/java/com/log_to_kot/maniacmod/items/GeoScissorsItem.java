package com.log_to_kot.maniacmod.items;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;

import java.util.function.Consumer;

/**
 * Geo-ножиці — предмет для розмінування пасток (ТЗ 4).
 *
 * ── Що тут, а що поза цим класом ──────────────────────────────────
 * Це лише ВИГЛЯД предмета (geo-модель/анімація) — як {@link CrowbarItem}.
 * ПКМ ножицями кидає ПРОМІНЬ погляду проти відрізків дротів
 * ({@code TrapModule#onScissorsUse}). Ванільний приціл сутності не
 * підходить: у дроту немає хітбокса (AABB діагоналі — великий порожній
 * прямокутник), тож ціль визначає саме промінь. Сама логіка живе в
 * {@code TrapModule}; предмет лише передає туди «використали».
 *
 * ── Одноразовість ─────────────────────────────────────────────────
 * За ТЗ 4/8 предмет витрачається після успішного розмінування: сама
 * операція зменшує стек на один (див. {@code TrapModule.onWireScissors}).
 * Тут жодного лічильника не тримаємо — «витрачений» тут означає
 * «зник зі стека», а не «залишився, але непрацездатний».
 *
 * ── Активація на землі ────────────────────────────────────────────
 * Використовує спільний конвеєр geo-предметів ({@code ItemGeoRenderer} +
 * {@link ItemVisuals}): достатньо трьох файлів
 * {@code geo/item/geo_scissors.geo.json},
 * {@code animations/item/geo_scissors.animation.json},
 * {@code textures/item/geo_scissors.png} та одного рядка в
 * {@link ItemRegistry}. Файлів-заглушок тут немає — вони додаються
 * художником (ТЗ 10).
 */
public class GeoScissorsItem extends net.minecraft.world.item.Item implements GeoItem {

    private static final ItemVisuals VISUALS = ItemVisuals.of("geo_scissors");

    private static final String CONTROLLER_NAME = "geoScissorsController";
    private static final RawAnimation ANIM_IDLE =
        RawAnimation.begin().thenLoop(VISUALS.animationSet().idle());

    private final AnimatableInstanceCache geoCache = new SingletonAnimatableInstanceCache(this);

    public GeoScissorsItem() {
        // stacksTo(1), як лом: розмінування — це одна конкретна дія, а не
        // ресурс, який возять стосами. За ТЗ предмет і так одноразовий.
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Рішення — лише на сервері; клієнт не знає, чи влучив (немає стану
        // пасток), тож нічого не «вгадує» і не змахує вхолосту.
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(stack);
        }
        var match = ManiacMod.match();
        if (match != null && match.traps().onScissorsUse(serverPlayer)) {
            return InteractionResultHolder.success(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public void initializeClient(Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
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
