package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.SpikeTrapEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Рендерер шипів: Geo-модель з анімаціями за станом сутності.
 *
 * ── Світло ───────────────────────────────────────────────────────────
 * Блокове світло моделі піднято до мінімуму {@link #MIN_BLOCK_LIGHT}: пастка
 * не має «вимикатись» у темряві чорним силуетом — вона видима й маньяку, й
 * виживим (як і димова міна). Червоно-чорна рамка читається як «небезпека».
 *
 * ── Стани ────────────────────────────────────────────────────────────
 * Стан (ARMED / EXTENDED / RETRACTING) приходить із сутності, анімацію
 * перемикає сама {@link SpikeTrapEntity}; рендерер лише малює.
 */
@OnlyIn(Dist.CLIENT)
public class SpikeTrapRenderer extends GeoEntityRenderer<SpikeTrapEntity> {

    /** Мінімальне блокове світло моделі (0..15): у темряві пастка не чорна. */
    private static final int MIN_BLOCK_LIGHT = 9;

    public SpikeTrapRenderer(EntityRendererProvider.Context context) {
        super(context, new SpikeTrapGeoModel());
        this.shadowRadius = 0.0f;
    }

    @Override
    public void render(SpikeTrapEntity trap, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        int light = LightTexture.pack(
            Math.max(LightTexture.block(packedLight), MIN_BLOCK_LIGHT),
            LightTexture.sky(packedLight));
        super.render(trap, entityYaw, partialTick, pose, buffers, light);
    }
}
