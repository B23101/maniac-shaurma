package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.RopeTrapEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

/**
 * Geo-модель мотузки на підлозі (єдине, що видно, доки пастка не
 * спрацювала). Файли: {@code geo/entity/rope_trap.geo.json},
 * {@code animations/entity/rope_trap.animation.json} (анімація {@code idle}),
 * {@code textures/entity/rope_trap.png}. Заглушка — бухта мотузки;
 * художник замінює файли, лишивши кістку {@code root} і назву {@code idle}.
 */
@OnlyIn(Dist.CLIENT)
public class RopeTrapGeoModel extends GeoModel<RopeTrapEntity> {

    @Override
    public ResourceLocation getModelResource(RopeTrapEntity e) {
        return new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/rope_trap.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(RopeTrapEntity e) {
        return new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/rope_trap.png");
    }

    @Override
    public ResourceLocation getAnimationResource(RopeTrapEntity e) {
        return new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/rope_trap.animation.json");
    }
}
