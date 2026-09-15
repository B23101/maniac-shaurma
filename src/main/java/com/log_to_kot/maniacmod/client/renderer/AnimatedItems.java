package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.*;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import software.bernie.geckolib.util.GeckoLibUtil;

// ─────────────────────────────────────────────────────────────────────────────
// BASE animated item — extend for each custom item
// ─────────────────────────────────────────────────────────────────────────────
class AnimatedItemBase extends Item implements GeoItem {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final String itemName;

    public AnimatedItemBase(Properties props, String itemName) {
        super(props);
        this.itemName = itemName;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        registrar.add(new AnimationController<>(this, "controller", 5, state -> {
            // Default: play idle loop
            return state.setAndContinue(
                RawAnimation.begin().thenLoop("animation." + itemName + ".idle")
            );
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    public String getItemName() { return itemName; }
}

// ─────────────────────────────────────────────────────────────────────────────
// Generic GeoModel for items — reads from assets/maniacmod/geo/item/<name>.geo.json
// ─────────────────────────────────────────────────────────────────────────────
class ManiacItemModel<T extends AnimatedItemBase> extends GeoModel<T> {
    @Override
    public ResourceLocation getModelResource(T item) {
        return new ResourceLocation(ManiacMod.MOD_ID, "geo/item/" + item.getItemName() + ".geo.json");
    }
    @Override
    public ResourceLocation getTextureResource(T item) {
        return new ResourceLocation(ManiacMod.MOD_ID, "textures/item/" + item.getItemName() + ".png");
    }
    @Override
    public ResourceLocation getAnimationResource(T item) {
        return new ResourceLocation(ManiacMod.MOD_ID, "animations/item/" + item.getItemName() + ".animation.json");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Generic renderer — one per item type, used in ClientSetup
// ─────────────────────────────────────────────────────────────────────────────
class ManiacItemRenderer<T extends AnimatedItemBase> extends GeoItemRenderer<T> {
    public ManiacItemRenderer() { super(new ManiacItemModel<>()); }
}

// =============================================================================
// SURVIVOR ITEMS  (each maps to its own Blockbench .geo.json + .animation.json)
// =============================================================================

// Anim: idle (floating), use (inject)
class AnimatedMedkit extends AnimatedItemBase {
    public AnimatedMedkit() { super(new Properties().stacksTo(1), "medkit"); }
}

// Anim: idle (spin slow), use (wrench turn)
class AnimatedWrench extends AnimatedItemBase {
    public AnimatedWrench() { super(new Properties().stacksTo(1), "wrench"); }
}

// Anim: idle, use (screw rotate)
class AnimatedScrewdriver extends AnimatedItemBase {
    public AnimatedScrewdriver() { super(new Properties().stacksTo(1), "screwdriver"); }
}

// Anim: idle (open/close blades), use (snip)
class AnimatedScissors extends AnimatedItemBase {
    public AnimatedScissors() { super(new Properties().stacksTo(1), "scissors"); }
}

// Anim: idle, swing (bat smash)
class AnimatedBat extends AnimatedItemBase {
    public AnimatedBat() { super(new Properties().stacksTo(1).durability(10), "bat"); }
}

// Anim: idle, use (zap spark)
class AnimatedTaser extends AnimatedItemBase {
    public AnimatedTaser() { super(new Properties().stacksTo(1).durability(3), "taser"); }
}

// Anim: idle, use (pry motion)
class AnimatedCrowbar extends AnimatedItemBase {
    public AnimatedCrowbar() { super(new Properties().stacksTo(1).durability(20), "crowbar"); }
}

// =============================================================================
// MANIAC ITEMS
// =============================================================================

// Anim: idle (menacing sway), swing (slash)
class AnimatedManiacWeapon extends AnimatedItemBase {
    public AnimatedManiacWeapon() { super(new Properties().stacksTo(1), "maniac_weapon"); }
}

// Anim: idle (jaws open), place (snap shut)
class AnimatedBearTrap extends AnimatedItemBase {
    public AnimatedBearTrap() { super(new Properties().stacksTo(3), "bear_trap"); }
}

// Anim: idle (coiled), throw (uncoil)
class AnimatedRope extends AnimatedItemBase {
    public AnimatedRope() { super(new Properties().stacksTo(3), "rope"); }
}

// Anim: idle (sparks), place (stretch)
class AnimatedElectricWire extends AnimatedItemBase {
    public AnimatedElectricWire() { super(new Properties().stacksTo(3), "electric_wire"); }
}
