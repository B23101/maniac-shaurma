package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.HookEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

/** ТЕСТОВА модель гака. Замінюється заміною трьох файлів (geo/texture/animation) без змін у коді. */
@OnlyIn(Dist.CLIENT)
public class HookGeoModel extends GeoModel<HookEntity> {
    @Override public ResourceLocation getModelResource(HookEntity e) {
        return new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/hook.geo.json");
    }
    @Override public ResourceLocation getTextureResource(HookEntity e) {
        return new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/hook.png");
    }
    @Override public ResourceLocation getAnimationResource(HookEntity e) {
        return new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/hook.animation.json");
    }
}
