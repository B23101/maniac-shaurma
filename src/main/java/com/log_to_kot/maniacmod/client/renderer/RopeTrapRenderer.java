package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.rope.RopeHangClient;
import com.log_to_kot.maniacmod.entity.RopeTrapEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Рендер мотузки-пастки.
 *
 * До спрацювання — geo-модель бухти на підлозі. Після — модель ховається, а
 * малюється об'ємна текстурована нитка від точки на стелі до ніг жертви
 * ({@link ThreadRenderer}). Ноги рахуються від ФАКТИЧНОЇ (інтерпольованої)
 * позиції жертви й кута маятника, тож нитка не відстає від тіла.
 */
@OnlyIn(Dist.CLIENT)
public class RopeTrapRenderer extends GeoEntityRenderer<RopeTrapEntity> {

    private static final ResourceLocation THREAD_TEX =
        new ResourceLocation(ManiacMod.MOD_ID, "textures/entity/rope_thread.png");

    /** Товщина нитки, блоків. */
    private static final float THICKNESS = 0.05f;

    public RopeTrapRenderer(EntityRendererProvider.Context context) {
        super(context, new RopeTrapGeoModel());
        this.shadowRadius = 0f;
    }

    @Override
    public void render(RopeTrapEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (!entity.isSnapped()) {
            super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
            return;
        }
        Entity victim = entity.level().getEntity(entity.victimId());
        if (victim == null) return;

        Vec3 origin = entity.getPosition(partialTick);
        Vec3 top = entity.ceilingPoint().subtract(origin);

        double theta = entity.swing(partialTick);
        Vec3 axis = entity.swingAxis();
        double s = Math.sin(theta);
        double c = Math.cos(theta);
        double f = RopeHangClient.FEET_FROM_CENTER;

        Vec3 center = victim.getPosition(partialTick).add(0.0, 1.0, 0.0);
        Vec3 feet = center.add(-axis.x * s * f, c * f, -axis.z * s * f).subtract(origin);

        int light = LevelRenderer.getLightColor(entity.level(),
            BlockPos.containing(victim.getX(), victim.getY() + 1.0, victim.getZ()));
        ThreadRenderer.render(poseStack, bufferSource, THREAD_TEX, top, feet, THICKNESS, light);
    }
}
