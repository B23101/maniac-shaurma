package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.HelicopterEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** Вертоліт: ніс уздовж руху (yaw сутності). Модель дивиться в +Z. */
@OnlyIn(Dist.CLIENT)
public class HelicopterRenderer extends GeoEntityRenderer<HelicopterEntity> {

    public HelicopterRenderer(EntityRendererProvider.Context context) {
        super(context, new HelicopterGeoModel());
        this.shadowRadius = 0f;
    }

    @Override
    public void render(HelicopterEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        float yaw = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
        poseStack.mulPose(Axis.YP.rotationDegrees(180f - yaw));
        super.render(entity, 0f, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }
}
