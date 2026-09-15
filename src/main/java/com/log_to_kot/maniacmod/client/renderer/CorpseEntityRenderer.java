package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.CorpseEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Renders CorpseEntity using GeckoLib.
 * The model (corpse.geo.json) is already rotated flat via root bone rotation [90,0,0],
 * so no extra transform is needed here.
 */
@OnlyIn(Dist.CLIENT)
public class CorpseEntityRenderer extends GeoEntityRenderer<CorpseEntity> {

    public CorpseEntityRenderer(EntityRendererProvider.Context context) {
        super(context, new CorpseEntityModel());
    }

    @Override
    public void render(CorpseEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        // Slightly sink into ground so the flat body doesn't float
        poseStack.translate(0, -0.1, 0);
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }
}
