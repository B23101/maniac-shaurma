package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.ManiacType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.*;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * The Maniac entity.
 * Height drives FOV for whoever is playing as this maniac (see ManiacFOVHandler).
 *
 * Scale variants:
 *   SMALL  → eyeHeight 1.4f  (child-sized, very low camera)
 *   NORMAL → eyeHeight 1.62f (default player height)
 *   TALL   → eyeHeight 2.2f  (taller, wider view angle)
 *   GIANT  → eyeHeight 3.0f  (giant, panoramic FOV)
 */
public class ManiacEntity extends PathfinderMob implements GeoEntity {

    public enum ManiacScale {
        SMALL, NORMAL, TALL, GIANT;

        public float getFOVMultiplier() {
            return switch (this) {
                case SMALL  -> 0.85f;
                case NORMAL -> 1.0f;
                case TALL   -> 1.15f;
                case GIANT  -> 1.35f;
            };
        }
    }

    // Animation names are built dynamically: "animation.<type_name>.<action>"
    public String anim(String action) {
        String prefix = (maniacType == ManiacType.CHUCKY) ? "chucky"
                      : (maniacType == ManiacType.SLENDERMAN) ? "slenderman"
                      : "chucky";
        return "animation." + prefix + "." + action;
    }

    private ManiacScale scale = ManiacScale.NORMAL;
    private ManiacType maniacType = null;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public ManiacEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    // ── Scale / height ───────────────────────────────────────────────────────

    public void setScale(ManiacScale scale) {
        this.scale = scale;
    }

    /** Returns the ManiacScale enum — renamed to avoid clash with LivingEntity.getScale() */
    public ManiacScale getManiacScale() { return scale; }

    public void setManiacType(ManiacType type) { this.maniacType = type; }
    public ManiacType getManiacType() { return maniacType; }

    /**
     * Eye height in blocks — drives camera FOV via ManiacFOVHandler.
     */
    @Override
    public float getEyeHeight(net.minecraft.world.entity.Pose pose) {
        return switch (scale) {
            case SMALL  -> 1.0f;
            case NORMAL -> 1.62f;
            case TALL   -> 2.0f;
            case GIANT  -> 2.8f;
        };
    }

    /**
     * Physical hitbox height.
     */
    public float getModelHeight() {
        return switch (scale) {
            case SMALL  -> 1.2f;
            case NORMAL -> 1.8f;
            case TALL   -> 2.4f;
            case GIANT  -> 3.2f;
        };
    }

    /**
     * Base FOV modifier for this scale.
     * 1.0 = normal (70°). Values > 1 = wider.
     */
    public float getFOVMultiplier() {
        return switch (scale) {
            case SMALL  -> 0.85f;  // narrow — low to ground, limited view
            case NORMAL -> 1.0f;   // standard
            case TALL   -> 1.15f;  // slightly wider
            case GIANT  -> 1.35f;  // panoramic
        };
    }

    // ── GeckoLib animation registration ─────────────────────────────────────

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {

        // Main movement controller
        registrar.add(new AnimationController<>(this, "movement_controller", 5, state -> {
            if (state.isMoving()) {
                double speed = this.getDeltaMovement().horizontalDistance();
                if (speed > 0.18) {
                    return state.setAndContinue(RawAnimation.begin().thenLoop(anim("run")));
                }
                return state.setAndContinue(RawAnimation.begin().thenLoop(anim("walk")));
            }
            return state.setAndContinue(RawAnimation.begin().thenLoop(anim("idle")));
        }));

        // Action controller (attack, traps, rope) — triggered externally
        registrar.add(new AnimationController<>(this, "action_controller", 2, state ->
            PlayState.STOP
        ));
    }

    /** Play a one-shot action animation (attack, place trap, etc.) */
    public void triggerAnim(String controllerName, String animName) {
        this.triggerAnim(controllerName, animName);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    // ── GeckoLib model/anim resource locations ───────────────────────────────

    /** Path to the Blockbench-exported .geo.json model */
    public ResourceLocation getModelResource() {
        return new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/maniac.geo.json");
    }

    /** Path to the Blockbench-exported .animation.json */
    public ResourceLocation getAnimationResource() {
        return new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/maniac.animation.json");
    }

    /** Path to texture */
    public ResourceLocation getTextureResource() {
        return new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/maniac.png");
    }
}
