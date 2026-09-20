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
 *     містить дві анімації: {@code "animation.generator.idle"}
 *     (генератор ще не повністю полагоджений) і
 *     {@code "animation.generator.active"} (обидві стадії REPAIR і FUEL
 *     пройдено) — точні короткі імена {@code "idle"}/{@code "active"}
 *     (без префіксу {@code animation.generator.}) читає
 *     {@link GeneratorEntity#registerControllers} через
 *     {@code RawAnimation.begin().thenLoop(...)} — GeckoLib сам додає
 *     префікс {@code animation.<об'єкт молекули>.} при пошуку за коротким
 *     іменем усередині animation-файлу цієї ж сутності.
 * {@code assets/maniacmod/textures/entity/generator.png}       — текстура
 *
 * ── Поточний стан: тимчасова заглушка ─────────────────────────────────
 * Усі три файли зараз існують, але це не фінальна геометрія — проста
 * коробка-корпус із двома декоративними "патрубками" й панеллю, згенерована
 * як заглушка, щоб рендерер узагалі мав що показати й не падав. Художник
 * замінює всі три файли своїми фінальними версіями; єдина умова — зберегти
 * ті самі назви кісток, що вже читає {@code generator.animation.json}
 * ({@code root}, {@code panel}, {@code pipe_left}, {@code pipe_right}),
 * або привести назви кісток і назви в animation-файлі у відповідність
 * одночасно, якщо фінальна модель матиме інакшу структуру.
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
