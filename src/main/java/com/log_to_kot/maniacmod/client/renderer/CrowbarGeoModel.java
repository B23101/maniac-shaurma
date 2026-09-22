package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.items.CrowbarItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

/**
 * Geo-модель предмета лома.
 *
 * Файли: {@code geo/item/crowbar.geo.json}, {@code animations/item/crowbar.animation.json}
 * (одна loop-анімація {@code idle}), {@code textures/item/crowbar.png}.
 * Поточні файли — заглушка (прямий стрижень із загнутим кінцем), як у
 * {@link FuelCanisterGeoModel}: художник замінює, лишаючи кістку {@code root}.
 */
@OnlyIn(Dist.CLIENT)
public class CrowbarGeoModel extends GeoModel<CrowbarItem> {

    @Override
    public ResourceLocation getModelResource(CrowbarItem item) {
        return new ResourceLocation(ManiacMod.MOD_ID, "geo/item/crowbar.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(CrowbarItem item) {
        return new ResourceLocation(ManiacMod.MOD_ID, "textures/item/crowbar.png");
    }

    @Override
    public ResourceLocation getAnimationResource(CrowbarItem item) {
        return new ResourceLocation(ManiacMod.MOD_ID, "animations/item/crowbar.animation.json");
    }
}
