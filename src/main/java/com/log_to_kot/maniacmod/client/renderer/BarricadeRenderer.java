package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.barricade.BoardSpec;
import com.log_to_kot.maniacmod.client.barricade.BoardAnimator;
import com.log_to_kot.maniacmod.client.barricade.PlankRenderer;
import com.log_to_kot.maniacmod.entity.BarricadeEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/** Малює всі дошки гнучкої барикади — тим самим {@link PlankRenderer}, що й режим будування. */
@OnlyIn(Dist.CLIENT)
public class BarricadeRenderer extends EntityRenderer<BarricadeEntity> {

    public BarricadeRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0f;
    }

    @Override
    public void render(BarricadeEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        entity.syncVisuals();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        Vec3 origin = entity.position();
        double now = entity.level().getGameTime() + partialTick;
        List<BoardSpec> boards = entity.boards();
        for (int i = 0; i < boards.size(); i++) {
            BoardSpec board = boards.get(i);
            // Поза залежить від цвяхів: розхитана / висить на одному / лежить на підлозі.
            BoardSpec shown = BoardAnimator.pose(entity, i, board, now);
            Vec3 mid = shown.a().add(shown.b()).scale(0.5).add(shown.normal().scale(0.15));
            int light = LevelRenderer.getLightColor(entity.level(), BlockPos.containing(mid));
            PlankRenderer.draw(pose, vc, shown, board.a(), origin, light, 1.0f);
        }
    }

    @Override
    public ResourceLocation getTextureLocation(BarricadeEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
