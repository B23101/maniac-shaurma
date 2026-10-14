package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.ReceiverEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

@OnlyIn(Dist.CLIENT)
public class ReceiverGeoModel extends GeoModel<ReceiverEntity> {
    @Override public ResourceLocation getModelResource(ReceiverEntity e) {
        return new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/receiver.geo.json");
    }
    @Override public ResourceLocation getTextureResource(ReceiverEntity e) {
        return new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/receiver.png");
    }
    @Override public ResourceLocation getAnimationResource(ReceiverEntity e) {
        return new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/receiver.animation.json");
    }
}
