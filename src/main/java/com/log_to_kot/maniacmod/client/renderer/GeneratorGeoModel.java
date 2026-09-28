package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.GeneratorEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

/**
 * Geo-модель сутності генератора.
 *
 * ── Файли асетів ─────────────────────────────────────────────────────
 * {@code assets/maniacmod/geo/entity/generator.geo.json}       — модель
 * {@code assets/maniacmod/animations/entity/generator.animation.json} —
 *     містить анімації {@code "idle"} (генератор ще не повністю
 *     полагоджений), {@code "running"} (обидві стадії REPAIR і FUEL
 *     пройдено) і одноразову {@code "boom"} (момент вибуху).
 *     {@link GeneratorEntity#registerControllers} читає саме ці короткі
 *     імена через {@code RawAnimation.begin().thenLoop(...)}/{@code
 *     .thenPlay(...)}. GeckoLib 4 шукає анімацію ЗА КЛЮЧЕМ ЯК ВІН Є у
 *     файлі — жодного префіксу {@code animation.<об'єкт>.} сам не
 *     додає, тому ключі тут мусять бути рівно такими, як у файлі.
 * {@code assets/maniacmod/textures/entity/generator.png}       — текстура
 */
@OnlyIn(Dist.CLIENT)
public class GeneratorGeoModel extends GeoModel<GeneratorEntity> {

    @Override
    public ResourceLocation getModelResource(GeneratorEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/generator.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(GeneratorEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/generator.png");
    }

    @Override
    public ResourceLocation getAnimationResource(GeneratorEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/generator.animation.json");
    }
}
