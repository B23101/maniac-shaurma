package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.items.FuelCanisterItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

/**
 * Geo-модель предмета каністри з бензином.
 *
 * ── Файли асетів ─────────────────────────────────────────────────────
 * {@code assets/maniacmod/geo/item/fuel_canister.geo.json}       — модель
 * {@code assets/maniacmod/animations/item/fuel_canister.animation.json} —
 *     одна loop-анімація {@code "idle"} (легке погойдування в руці) —
 *     той самий короткий-ім'я підхід, що {@code GeneratorGeoModel}.
 * {@code assets/maniacmod/textures/item/fuel_canister.png}       — текстура
 *
 * ── Поточний стан: тимчасова заглушка ─────────────────────────────────
 * Як і {@code GeneratorGeoModel}: усі три файли існують, але це не
 * фінальна геометрія — простий прямокутний корпус каністри з ручкою
 * зверху, згенерований як заглушка, щоб рендерер мав що показати.
 * Художник замінює файли своїми фінальними версіями; єдина умова —
 * зберегти назву кістки {@code root} (або привести назву в
 * animation-файлі у відповідність, якщо структура зміниться).
 */
@OnlyIn(Dist.CLIENT)
public class FuelCanisterGeoModel extends GeoModel<FuelCanisterItem> {

    @Override
    public ResourceLocation getModelResource(FuelCanisterItem item) {
        return new ResourceLocation(ManiacMod.MOD_ID, "geo/item/fuel_canister.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(FuelCanisterItem item) {
        return new ResourceLocation(ManiacMod.MOD_ID, "textures/item/fuel_canister.png");
    }

    @Override
    public ResourceLocation getAnimationResource(FuelCanisterItem item) {
        return new ResourceLocation(ManiacMod.MOD_ID, "animations/item/fuel_canister.animation.json");
    }
}
