package com.log_to_kot.maniacmod.client.victory;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.victory.VictoryHookEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

/**
 * Модель гачка з «опудалом». Текстура моделі — власна (іржаве залізо); скін жертви підставляє
 * рендерер, але лише для кісток {@code player_*}. Геометрія має дві версії (руки 4 і 3 px), бо
 * skin-UV вузьких рук інакше з'їжджає.
 */
@OnlyIn(Dist.CLIENT)
public class VictoryHookModel extends GeoModel<VictoryHookEntity> {

    private static final ResourceLocation GEO_WIDE =
        new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/victory_hook.geo.json");
    private static final ResourceLocation GEO_SLIM =
        new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/victory_hook_slim.geo.json");
    private static final ResourceLocation TEXTURE =
        new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/victory_hook.png");
    private static final ResourceLocation ANIMATION =
        new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/victory_hook.animation.json");

    @Override
    public ResourceLocation getModelResource(VictoryHookEntity e) {
        return e.isOccupied() && VictorySkinResolver.slim(e) ? GEO_SLIM : GEO_WIDE;
    }

    @Override public ResourceLocation getTextureResource(VictoryHookEntity e)   { return TEXTURE; }
    @Override public ResourceLocation getAnimationResource(VictoryHookEntity e) { return ANIMATION; }
}
