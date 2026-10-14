package com.log_to_kot.maniacmod.client.victory;

import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacVisuals;
import com.log_to_kot.maniacmod.maniacs.TestManiacArchetype;
import com.log_to_kot.maniacmod.victory.VictoryManiacEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** Малює сутність-маніяка сцени моделлю архетипу, який грав раунд (шляхи — з {@link ManiacVisuals}). */
@OnlyIn(Dist.CLIENT)
public class VictoryManiacRenderer extends GeoEntityRenderer<VictoryManiacEntity> {

    public VictoryManiacRenderer(EntityRendererProvider.Context context) {
        super(context, new Model());
        this.shadowRadius = 0.7f;
    }

    @Override
    public void render(VictoryManiacEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        // GeckoLib для звичайної (не Living) сутності сам додає розворот 180° (applyRotations: 180 - yaw),
        // тож тут лише -heading. Раніше було ще й "+ 180f" — персонажі й гачки дивились навпаки точці розмітки.
        poseStack.mulPose(Axis.YP.rotationDegrees(-entity.heading()));
        super.render(entity, 0f, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }

    private static final class Model extends GeoModel<VictoryManiacEntity> {
        private static ManiacVisuals visuals(VictoryManiacEntity e) {
            ManiacArchetype archetype = e.archetype();
            return archetype != null ? archetype.visuals() : ManiacVisuals.of(TestManiacArchetype.ID);
        }
        @Override public ResourceLocation getModelResource(VictoryManiacEntity e)     { return visuals(e).geoModel(); }
        @Override public ResourceLocation getTextureResource(VictoryManiacEntity e)   { return visuals(e).texture(); }
        @Override public ResourceLocation getAnimationResource(VictoryManiacEntity e) { return visuals(e).animations(); }
    }
}
