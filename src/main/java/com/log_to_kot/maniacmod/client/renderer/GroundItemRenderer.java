package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.GroundItemEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Рендер лежачого предмета.
 *
 * Предмет повернутий на власний випадковий кут по X (по Y завжди 0) —
 * тому дві однакові аптечки поруч не виглядають копіями.
 *
 * ⚠ Зараз малюється baked-модель предмета. Коли geo-моделі предметів
 * буде підключено (assets/maniacmod/geo/item/*.geo.json), цей клас
 * замінюється на GeckoLib-рендерер — інтерфейс сутності при цьому не
 * змінюється, бо вона вже віддає item() і spin().
 */
@OnlyIn(Dist.CLIENT)
public class GroundItemRenderer extends EntityRenderer<GroundItemEntity> {

    private final ItemRenderer itemRenderer;

    public GroundItemRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
        this.shadowRadius = 0.15f;
    }

    @Override
    public void render(GroundItemEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        Item item = entity.item();
        if (item == null) return;

        ItemStack stack = new ItemStack(item);
        poseStack.pushPose();

        // Поворот по X — власний кут предмета, по Y — завжди 0.
        poseStack.translate(0.0, 0.08, 0.0);
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(GroundItemEntity.ROTATION_Y));
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(entity.rotationX()));
        poseStack.scale(0.6f, 0.6f, 0.6f);

        BakedModel model = itemRenderer.getModel(stack, entity.level(), null, entity.getId());
        itemRenderer.render(stack, ItemDisplayContext.GROUND, false, poseStack, buffers,
            packedLight, OverlayTexture.NO_OVERLAY, model);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(GroundItemEntity entity) {
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }
}
