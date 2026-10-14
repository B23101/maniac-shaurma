package com.log_to_kot.maniacmod.client.victory;

import com.log_to_kot.maniacmod.victory.VictoryHookEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Малює гачок: уся модель — своєю текстурою, а кістки {@code player_*} (тіло, голова,
 * руки, ноги, шари одягу) — скіном жертви. Порожній гачок кісток {@code player_*} не малює.
 *
 * <p>Підміна буфера на рівні кістки — звичайний прийом GeckoLib (так працюють glow-шари): діти
 * {@code player_body} успадковують новий буфер, а після повернення з рекурсії решта кісток
 * малюється вихідним.</p>
 */
@OnlyIn(Dist.CLIENT)
public class VictoryHookRenderer extends GeoEntityRenderer<VictoryHookEntity> {

    /** Усі кістки скіна жертви названі player_* (body, head, arm_r/l, leg_r/l). */
    private static final String SKIN_PREFIX = "player_";

    public VictoryHookRenderer(EntityRendererProvider.Context context) {
        super(context, new VictoryHookModel());
        this.shadowRadius = 0.5f;
    }

    @Override
    public void render(VictoryHookEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        // GeckoLib для звичайної (не Living) сутності сам додає розворот 180° (applyRotations: 180 - yaw),
        // тож тут лише -heading. Раніше було ще й "+ 180f" — персонажі й гачки дивились навпаки точці розмітки.
        poseStack.mulPose(Axis.YP.rotationDegrees(-entity.heading()));
        super.render(entity, 0f, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }

    /**
     * БАГ: скін жертви накладався на ВСЮ модель гачка, а не лише на кістки гравця.
     * Причина — підміна буфера на {@code player_body}: {@code MultiBufferSource.BufferSource}
     * при зміні RenderType завершує попередній буфер, тож усе, що малювалось далі «старим»
     * {@code buffer} (решта гачка), писалось у вже закритий/чужий буфер і виходило зі скіном.
     *
     * Тепер КОЖНА кістка бере свій буфер заново за власним RenderType: кістки {@code player_*}
     * (тіло, голова, руки, ноги, шари одягу) — зі скіном жертви, усі інші — зі справжньою
     * текстурою гачка. Діти {@code player_body} теж проходять через цей метод по одній, тож
     * успадковувати буфер від батька їм не потрібно.
     */
    @Override
    public void renderRecursively(PoseStack poseStack, VictoryHookEntity animatable, GeoBone bone,
                                  RenderType renderType, MultiBufferSource bufferSource,
                                  VertexConsumer buffer, boolean isReRender, float partialTick,
                                  int packedLight, int packedOverlay,
                                  float red, float green, float blue, float alpha) {
        if (bone.getName().startsWith(SKIN_PREFIX)) {
            // Порожній гачок кісток гравця не малює (і не обходить їхніх дітей).
            if (!animatable.isOccupied()) return;
            RenderType skinType = RenderType.entityCutoutNoCull(VictorySkinResolver.skin(animatable));
            super.renderRecursively(poseStack, animatable, bone, skinType, bufferSource,
                bufferSource.getBuffer(skinType), isReRender, partialTick,
                packedLight, packedOverlay, red, green, blue, alpha);
            return;
        }
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource,
            bufferSource.getBuffer(renderType), isReRender, partialTick,
            packedLight, packedOverlay, red, green, blue, alpha);
    }
}
