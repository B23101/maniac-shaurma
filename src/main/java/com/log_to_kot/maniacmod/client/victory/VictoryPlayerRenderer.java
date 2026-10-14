package com.log_to_kot.maniacmod.client.victory;

import com.log_to_kot.maniacmod.victory.VictoryPlayerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Малює гравця сцени перемоги. Живий — звичайний скін. Привид — той самий скін, але напівпрозорий
 * (translucent), підфарбований у блакитний відтінок привида й світиться (повна яскравість, щоб не
 * темнів у нічній сцені).
 *
 * <p>Ефект привида накладається на КОЖНУ кістку в {@link #renderRecursively}: буфер береться за
 * власним RenderType кістки (той самий прийом, що й у {@code VictoryHookRenderer}), а колір/альфа
 * множаться на відтінок.</p>
 */
@OnlyIn(Dist.CLIENT)
public class VictoryPlayerRenderer extends GeoEntityRenderer<VictoryPlayerEntity> {

    /** Прозорість привида: скін ще впізнаваний, але крізь нього видно сцену. */
    private static final float GHOST_ALPHA = 0.5f;
    /** Блакитний відтінок: червоного менше, зеленого трохи менше, синього повністю. */
    private static final float GHOST_R = 0.55f, GHOST_G = 0.82f, GHOST_B = 1.0f;

    public VictoryPlayerRenderer(EntityRendererProvider.Context context) {
        super(context, new VictoryPlayerModel());
        this.shadowRadius = 0.4f;
    }

    @Override
    public void render(VictoryPlayerEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        // GeckoLib для звичайної (не Living) сутності сам додає розворот 180° (applyRotations: 180 - yaw),
        // тож тут лише -heading. Раніше було ще й "+ 180f" — персонажі й гачки дивились навпаки точці розмітки.
        poseStack.mulPose(Axis.YP.rotationDegrees(-entity.heading()));
        super.render(entity, 0f, partialTick, poseStack, bufferSource,
            entity.isGhost() ? LightTexture.FULL_BRIGHT : packedLight);
        poseStack.popPose();
    }

    @Override
    public RenderType getRenderType(VictoryPlayerEntity animatable, ResourceLocation texture,
                                    MultiBufferSource bufferSource, float partialTick) {
        return animatable.isGhost()
            ? RenderType.entityTranslucent(texture)
            : RenderType.entityCutoutNoCull(texture);
    }

    @Override
    public void renderRecursively(PoseStack poseStack, VictoryPlayerEntity animatable, GeoBone bone,
                                  RenderType renderType, MultiBufferSource bufferSource,
                                  VertexConsumer buffer, boolean isReRender, float partialTick,
                                  int packedLight, int packedOverlay,
                                  float red, float green, float blue, float alpha) {
        if (animatable.isGhost()) {
            RenderType ghostType = RenderType.entityTranslucent(getTextureLocation(animatable));
            super.renderRecursively(poseStack, animatable, bone, ghostType, bufferSource,
                bufferSource.getBuffer(ghostType), isReRender, partialTick,
                LightTexture.FULL_BRIGHT, packedOverlay,
                red * GHOST_R, green * GHOST_G, blue * GHOST_B, alpha * GHOST_ALPHA);
            return;
        }
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource,
            bufferSource.getBuffer(renderType), isReRender, partialTick,
            packedLight, packedOverlay, red, green, blue, alpha);
    }
}
