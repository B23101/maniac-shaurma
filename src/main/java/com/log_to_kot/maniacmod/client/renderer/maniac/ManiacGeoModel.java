package com.log_to_kot.maniacmod.client.renderer.maniac;

import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacVisuals;
import com.log_to_kot.maniacmod.maniacs.TestManiacArchetype;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

/**
 * Гео-модель маньяка.
 *
 * ── Чому шляхи беруться ДИНАМІЧНО ────────────────────────────────────
 * Один рендерер обслуговує БУДЬ-ЯКОГО маньяка (модель замінює гравця, а
 * тип сутності в усіх один — {@code Player}). Тому шлях до асе́тів не
 * може бути константою класу: він читається з архетипу ГРАВЦЯ, який
 * зараз малюється ({@code ManiacAnimatable.archetype()}).
 *
 * ── Додати маньяка ───────────────────────────────────────────────────
 * Нічого тут правити не треба. Новий архетип повертає свої
 * {@link ManiacVisuals} (за замовчуванням — файли, названі його id), і
 * ця модель підхопить їх сама.
 *
 * ── Резервний шлях ───────────────────────────────────────────────────
 * GeckoLib може спитати ресурс до першого рендера (попередня компіляція
 * моделі). Тоді контексту ще немає — віддаємо асе́ти тестового маньяка,
 * щоб рушій мав валідний шлях і не падав на старті клієнта.
 */
@OnlyIn(Dist.CLIENT)
public class ManiacGeoModel extends GeoModel<ManiacAnimatable> {

    @Override
    public ResourceLocation getModelResource(ManiacAnimatable animatable) {
        return visuals().geoModel();
    }

    @Override
    public ResourceLocation getTextureResource(ManiacAnimatable animatable) {
        return visuals().texture();
    }

    @Override
    public ResourceLocation getAnimationResource(ManiacAnimatable animatable) {
        return visuals().animations();
    }

    private static ManiacVisuals visuals() {
        ManiacArchetype archetype = ManiacAnimatable.archetype();
        if (archetype != null) return archetype.visuals();
        return ManiacVisuals.of(TestManiacArchetype.ID);
    }
}
