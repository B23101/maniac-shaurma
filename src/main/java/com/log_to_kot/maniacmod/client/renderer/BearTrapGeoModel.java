package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.BearTrapEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

/**
 * Geo-модель капкана (сутність у світі).
 *
 * ── Файли асетів ─────────────────────────────────────────────────────
 * {@code assets/maniacmod/geo/entity/bear_trap.geo.json}          — модель
 * {@code assets/maniacmod/animations/entity/bear_trap.animation.json} —
 *     дві loop-анімації: {@code idle_open} (розкритий, чекає) і
 *     {@code idle_closed} (захлопнутий; його перші кадри — момент
 *     клацання, далі нерухома поза)
 * {@code assets/maniacmod/textures/entity/bear_trap.png}          — текстура
 *
 * ── Поточний стан: тимчасова заглушка ────────────────────────────────
 * Як і {@link GeneratorGeoModel}: усі файли існують, але це проста
 * геометрія (плита-основа + дві дуги-щелепи), щоб рендерер мав що
 * показати й механіку можна було перевірити в грі. Художник замінює
 * файли фінальними; умова — зберегти кістку {@code root} і назви двох
 * анімацій.
 */
@OnlyIn(Dist.CLIENT)
public class BearTrapGeoModel extends GeoModel<BearTrapEntity> {

    @Override
    public ResourceLocation getModelResource(BearTrapEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/bear_trap.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(BearTrapEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/bear_trap.png");
    }

    @Override
    public ResourceLocation getAnimationResource(BearTrapEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/bear_trap.animation.json");
    }
}
