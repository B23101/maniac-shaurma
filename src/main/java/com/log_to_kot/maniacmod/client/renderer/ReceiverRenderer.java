package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.ReceiverEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** Приймач. Поворот лише по X-полю сутності (= yaw точки розмітки); нахил по Y — 0. */
@OnlyIn(Dist.CLIENT)
public class ReceiverRenderer extends GeoEntityRenderer<ReceiverEntity> {

    public ReceiverRenderer(EntityRendererProvider.Context context) {
        super(context, new ReceiverGeoModel());
        this.shadowRadius = 0.5f;
    }

    @Override
    public void render(ReceiverEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(-entity.rotationX() + 180f));
        super.render(entity, 0f, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }
}
