package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.ManiacEntity;
import com.log_to_kot.maniacmod.entity.ManiacType;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeckoLib model: picks the correct .geo.json, .animation.json and .png
 * based on which ManiacType the entity is.
 *
 * Chucky:     geo/entity/chucky.*     textures/entity/chucky.png
 * Slenderman: geo/entity/slenderman.* textures/entity/slenderman.png
 */
public class ManiacEntityModel extends GeoModel<ManiacEntity> {

    private String nameFor(ManiacEntity entity) {
        ManiacType t = entity.getManiacType();
        if (t == ManiacType.CHUCKY)     return "chucky";
        if (t == ManiacType.SLENDERMAN) return "slenderman";
        return "chucky"; // fallback
    }

    @Override
    public ResourceLocation getModelResource(ManiacEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID,
            "geo/entity/" + nameFor(entity) + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ManiacEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID,
            "textures/entity/" + nameFor(entity) + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(ManiacEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID,
            "animations/entity/" + nameFor(entity) + ".animation.json");
    }
}
