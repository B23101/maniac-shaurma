package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.HelicopterEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

@OnlyIn(Dist.CLIENT)
public class HelicopterGeoModel extends GeoModel<HelicopterEntity> {
    @Override public ResourceLocation getModelResource(HelicopterEntity e) {
        return new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/helicopter.geo.json");
    }
    @Override public ResourceLocation getTextureResource(HelicopterEntity e) {
        return new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/helicopter.png");
    }
    @Override public ResourceLocation getAnimationResource(HelicopterEntity e) {
        return new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/helicopter.animation.json");
    }
}
