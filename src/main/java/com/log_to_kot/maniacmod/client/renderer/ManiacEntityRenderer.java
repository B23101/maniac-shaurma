package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.ManiacEntity;
import com.log_to_kot.maniacmod.entity.ManiacType;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Renders ManiacEntity.
 * Scale is driven by ManiacType.modelScale (relative to normal 1.8-block player):
 *   CHUCKY     → 0.278  (tiny doll)
 *   SLENDERMAN → 2.222  (towering figure)
 */
public class ManiacEntityRenderer extends GeoEntityRenderer<ManiacEntity> {

    public ManiacEntityRenderer(EntityRendererProvider.Context context) {
        super(context, new ManiacEntityModel());
    }

    @Override
    public void render(ManiacEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();

        float scale = entity.getManiacType() != null
            ? entity.getManiacType().modelScale
            : 1.0f;

        poseStack.scale(scale, scale, scale);
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }
}
