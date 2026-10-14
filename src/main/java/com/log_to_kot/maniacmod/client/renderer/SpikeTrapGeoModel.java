package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.SpikeTrapEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;

/**
 * Geo-модель шипів (сутність у світі).
 *
 * ── Файли асетів ─────────────────────────────────────────────────────
 * {@code assets/maniacmod/geo/entity/spike_trap.geo.json}            — модель
 * {@code assets/maniacmod/animations/entity/spike_trap.animation.json}
 *     — {@code idle} (зведені: короткі вістря «дихають»), {@code spike_out}
 *       (удар знизу: викид із перельотом, вібрація, кров на вістрях),
 *       {@code spike_in} (шипи ховаються перед зникненням)
 * {@code assets/maniacmod/textures/entity/spike_trap.png}            — текстура 64×64
 *
 * Художник може замінити файли; умова — зберегти кістки {@code spikes} і
 * {@code spike_0..8} / {@code blood_0..8} та назви анімацій.
 */
@OnlyIn(Dist.CLIENT)
public class SpikeTrapGeoModel extends GeoModel<SpikeTrapEntity> {

    @Override
    public ResourceLocation getModelResource(SpikeTrapEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "geo/entity/spike_trap.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(SpikeTrapEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/spike_trap.png");
    }

    @Override
    public ResourceLocation getAnimationResource(SpikeTrapEntity entity) {
        return new ResourceLocation(ManiacMod.MOD_ID, "animations/entity/spike_trap.animation.json");
    }
}
