package com.log_to_kot.maniacmod.client.victory;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.victory.VictoryPlayerEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

/**
 * Модель персонажа гравця (геометрія зі snipers_shaurma {@code airplane_character}; ті самі назви
 * кісток, тож танці з Blockbench для тієї моделі підійдуть і тут). Дві геометрії — з руками 4 і 3 px
 * (slim): інакше UV вузьких рук їде. Текстура — скін гравця, не файл мода.
 */
@OnlyIn(Dist.CLIENT)
public class VictoryPlayerModel extends GeoModel<VictoryPlayerEntity> {

    private static final ResourceLocation GEO_WIDE =
        new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/victory_player.geo.json");
    private static final ResourceLocation GEO_SLIM =
        new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/victory_player_slim.geo.json");
    private static final ResourceLocation ANIMATION =
        new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/victory_player.animation.json");

    @Override
    public ResourceLocation getModelResource(VictoryPlayerEntity e) {
        return VictorySkinResolver.slim(e.skinUuid(), e.skinTexture(), e.skinSignature()) ? GEO_SLIM : GEO_WIDE;
    }

    @Override
    public ResourceLocation getTextureResource(VictoryPlayerEntity e) {
        return VictorySkinResolver.skin(e.skinUuid(), e.skinTexture(), e.skinSignature());
    }

    @Override
    public ResourceLocation getAnimationResource(VictoryPlayerEntity e) { return ANIMATION; }
}
