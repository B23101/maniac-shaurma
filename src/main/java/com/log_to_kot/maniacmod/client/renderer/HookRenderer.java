package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.HookEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Гак. Поворот СТРОГО по {@link HookEntity#heading()} (yaw точки розмітки), нахил завжди 0 —
 * та сама формула, що в {@code ReceiverRenderer}: модель, намальована лицем у -Z, дивиться
 * туди, куди дивився адмін. Саме від цього напрямку рахується положення жертви на гаку.
 */
@OnlyIn(Dist.CLIENT)
public class HookRenderer extends GeoEntityRenderer<HookEntity> {

    public HookRenderer(EntityRendererProvider.Context context) {
        super(context, new HookGeoModel());
        this.shadowRadius = 0.4f;
    }

    @Override
    public void render(HookEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(-entity.heading() + 180f));
        super.render(entity, 0f, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }
}
