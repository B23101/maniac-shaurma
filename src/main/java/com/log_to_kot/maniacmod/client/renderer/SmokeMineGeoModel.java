package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.SmokeMineEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

/**
 * Geo-модель димової міни (сутність у світі).
 *
 * ── Файли асетів ─────────────────────────────────────────────────────
 * {@code assets/maniacmod/geo/entity/smoke_mine.geo.json}            — модель
 * {@code assets/maniacmod/animations/entity/smoke_mine.animation.json}
 *     — {@code idle}: світлодіод блимає раз на секунду
 * {@code assets/maniacmod/textures/entity/smoke_mine.png}            — текстура
 * {@code assets/maniacmod/textures/entity/smoke_mine_glowmask.png}
 *     — маска світіння для {@code AutoGlowingGeoLayer}: пікселі, що горять
 *       у темряві (світлодіод). Ім'я фіксоване суфіксом {@code _glowmask}.
 *
 * Художник може замінити файли; умова — зберегти кістки {@code root} і
 * {@code led} та назву анімації {@code idle}.
 */
@OnlyIn(Dist.CLIENT)
public class SmokeMineGeoModel extends GeoModel<SmokeMineEntity> {

    @Override
    public ResourceLocation getModelResource(SmokeMineEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/smoke_mine.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(SmokeMineEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/smoke_mine.png");
    }

    @Override
    public ResourceLocation getAnimationResource(SmokeMineEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/smoke_mine.animation.json");
    }
}
