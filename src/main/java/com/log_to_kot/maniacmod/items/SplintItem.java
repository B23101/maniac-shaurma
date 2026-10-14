package com.log_to_kot.maniacmod.items;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResultHolder;
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
 * Шина: лагодить поламану ногу (BROKEN_LEG → HEALTHY), одне
 * використання за матч (за таблицею items/README.md).
 *
 * ── Ефект ────────────────────────────────────────────────────────────
 * Поки нога поламана, {@code SurvivorModule.tickBrokenLegStamina}
 * щотік притискає стаміну гравця до нуля (див. клас-докстрінг того
 * методу). Єдиний спосіб зняти BROKEN_LEG — повернути стан у HEALTHY:
 * з наступного тіку tickBrokenLegStamina більше не спрацьовує (стан
 * уже не BROKEN_LEG), і стаміна відновлюється за звичайними правилами
 * StaminaRules (recoveryPerSecond/recoveryDelaySeconds).
 *
 * ── Чому не можна вжити при CRAWLING/UNCONSCIOUS ────────────────────
 * Хруст ноги лунає в момент падіння, але СТАН BROKEN_LEG вмикається
 * лише ПІСЛЯ вставання (onStandUpAttempt), не під час лежання. Поки гравець CRAWLING чи UNCONSCIOUS, pendingLegBreak
 * ще не застосований і survivorStateOf(...) не поверне BROKEN_LEG —
 * onUse природно відмовить (fail), нічого спеціально перевіряти не
 * треба понад сам стан.
 *
 * v3-еквівалент: items/ItemImplementations.java не мав цього предмета
 * взагалі (TODO у README items/) — поламана нога в v3 не існувала як
 * окремий стан.
 */
public class SplintItem extends ItemArchetype implements GeoItem {

    /** Вигляд: geo/item/splint.geo.json, textures/item/splint.png, animations/item/splint.animation.json. */
    private static final ItemVisuals VISUALS = ItemVisuals.of("splint");
    private static final RawAnimation ANIM_IDLE =
        RawAnimation.begin().thenLoop(VISUALS.animationSet().idle());

    private final AnimatableInstanceCache geoCache = new SingletonAnimatableInstanceCache(this);

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        com.log_to_kot.maniacmod.client.renderer.ItemGeoRenderer
            .attach(consumer, () -> new com.log_to_kot.maniacmod.client.renderer.ItemGeoRenderer<>(VISUALS));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "splint", 0,
                state -> state.setAndContinue(ANIM_IDLE)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    public SplintItem() {
        super(new Properties().stacksTo(1), /* cooldownTicks */ 0, /* maxUsesPerGame */ 1);
    }

    @Override
    protected InteractionResultHolder<ItemStack> onUse(ServerPlayer player, ItemStack stack) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return InteractionResultHolder.fail(stack);
        if (!match.phases().allows(PhaseRule.ITEM_USE)) return InteractionResultHolder.fail(stack);
        if (!match.isSurvivor(player.getUUID())) return InteractionResultHolder.fail(stack);
        if (match.survivorStateOf(player.getUUID()) != SurvivorState.BROKEN_LEG) {
            return InteractionResultHolder.fail(stack);
        }

        match.setSurvivorState(player.getUUID(), SurvivorState.HEALTHY);
        match.survivors().onSplintApplied(player);

        stack.shrink(1);
        return InteractionResultHolder.success(stack);
    }
}
