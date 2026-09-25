package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.items.ItemVisuals;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.model.GeoModel;

/**
 * Geo-модель БУДЬ-ЯКОГО предмета мода — одна на всі.
 *
 * ── Чому одна, а не модель на предмет ────────────────────────────────
 * Раніше на кожен предмет був свій клас ({@code CrowbarGeoModel},
 * {@code FuelCanisterGeoModel}), і всі вони відрізнялися РІВНО трьома
 * шляхами до асе́тів: копія класу заради трьох рядків. Тепер шляхи
 * приходить {@link ItemVisuals} (з них будується й рендерер), тож
 * моделей стільки ж, скільки КОНВЕНЦІЙ іменування, а не предметів.
 *
 * ── Що це дає при додаванні предмета ─────────────────────────────────
 * Нічого цього писати не треба: колір/модель/текстура/анімації
 * підхоплюються з id предмета (див. {@link ItemVisuals#of(String)}).
 */
@OnlyIn(Dist.CLIENT)
public class ItemGeoModel<T extends Item & GeoAnimatable> extends GeoModel<T> {

    private final ItemVisuals visuals;

    public ItemGeoModel(ItemVisuals visuals) {
        this.visuals = visuals;
    }

    /** Вигляд, з яким побудована ця модель — читає рендерер і діагностика. */
    public ItemVisuals visuals() {
        return visuals;
    }

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return visuals.geoModel();
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return visuals.texture();
    }

    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return visuals.animations();
    }
}
