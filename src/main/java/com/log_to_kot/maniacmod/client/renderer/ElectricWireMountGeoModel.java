package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.ElectricWireMountEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

/**
 * Geo-модель кріплення електричного дроту (ТЗ 2.3).
 *
 * ── Файли асетів ─────────────────────────────────────────────────────
 * {@code assets/maniacmod/geo/entity/electric_wire_mount.geo.json}
 * {@code assets/maniacmod/animations/entity/electric_wire_mount.animation.json}
 *     (одна loop-анімація {@code idle})
 * {@code assets/maniacmod/textures/entity/electric_wire_mount.png}
 *
 * ── Стан ─────────────────────────────────────────────────────────────
 * Власна модель стовпа 3×3×4 пікселі (не заглушка bear_trap): один куб
 * {@code root}, центрований по X/Z, що стоїть на верхній грані блока.
 * Модель орієнтується на {@code root}; напрямок задає
 * {@link ElectricWireMountRenderer}, тож у файлі достатньо, щоб модель
 * «дивилась» уздовж осі -Z (північ) у незміненому вигляді.
 */
@OnlyIn(Dist.CLIENT)
public class ElectricWireMountGeoModel extends GeoModel<ElectricWireMountEntity> {

    @Override
    public ResourceLocation getModelResource(ElectricWireMountEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/electric_wire_mount.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ElectricWireMountEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/electric_wire_mount.png");
    }

    @Override
    public ResourceLocation getAnimationResource(ElectricWireMountEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/electric_wire_mount.animation.json");
    }
}
