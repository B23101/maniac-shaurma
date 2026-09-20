package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.GroundItemEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Рендер предмета, що лежить на землі.
 *
 * <h3>Що малюється</h3>
 * Повний {@link ItemStack} сутності ({@link GroundItemEntity#stack()}) у
 * контексті {@link ItemDisplayContext#HEAD} — так, ніби предмет
 * надягнено на голову. Це саме той «head display», що просили: модель
 * береться така, як її бачить гравець, що носить предмет на голові, а не
 * окрема «земляна» проєкція.
 *
 * <h3>Чому HEAD, а не GROUND</h3>
 * {@code GROUND}-трансформ у {@code models/item/*.json} налаштований
 * під ванільний {@code ItemEntity}. Каністра, наприклад, уже має там
 * {@code translation [0,3,0]} і {@code scale 0.5} — якби ми малювали в
 * {@code GROUND}, то накладали б власний поворот і масштаб поверх
 * чужих, і результат залежав би від JSON кожного предмета. У
 * {@code fuel_canister.json} блоку {@code head} немає взагалі, тож
 * {@code HEAD} для неї — «чиста» модель без прихованих зсувів, а вигляд
 * регулюється лише тут ({@link #SCALE}, {@link #LIFT_BLOCKS}).
 *
 * <p><b>⚠ Потребує підгонки в грі.</b> {@link #SCALE} і
 * {@link #LIFT_BLOCKS} — стартові значення, не виміряні: розмір і
 * «низ» HEAD-моделі залежать від геометрії конкретного предмета.
 * Якщо каністра тоне в підлозі чи висить — правиться ці дві константи,
 * більше ніде.</p>
 *
 * <h3>Поворот</h3>
 * По X — власний випадковий кут сутності, по Y — завжди 0
 * ({@link GroundItemEntity#ROTATION_Y}); той самий принцип, що в
 * генератора й старої версії. Кут не змінюється, тож предмет лежить
 * так, як упав. Важливо: <b>кут береться з даних сутності, а не з
 * {@code entity.tickCount}</b>, тому предмет не «крутиться на місці» як
 * ванільний.
 *
 * <h3>Предмети-GeoItem (каністра)</h3>
 * Каністра має {@code parent: builtin/entity} і рендериться
 * {@code BlockEntityWithoutLevelRenderer}. {@code ItemRenderer.render}
 * сам викликає його для таких моделей, тож на землі показується та
 * сама geo-модель, що й у руці, — окремий код для неї не потрібен.
 *
 * <h3>Освітлення</h3>
 * Береться {@code packedLight} від рушія (світло в клітинці сутності).
 * Блиск від частинок його не змінює — вони самосвітні.
 */
@OnlyIn(Dist.CLIENT)
public class GroundItemRenderer extends EntityRenderer<GroundItemEntity> {

    /**
     * Скільки блоків підняти модель над точкою сутності (ноги стоять
     * на верхній грані блока). Стартове значення, підбирається в грі.
     */
    private static final double LIFT_BLOCKS = 0.05;

    /**
     * Масштаб моделі. Стартове значення, підбирається в грі: HEAD
     * розрахований на голову гравця, тому на землі його доводиться
     * зменшувати, щоб предмет не виглядав як капелюх завбільшки з голову.
     */
    private static final float SCALE = 0.55f;

    private final ItemRenderer itemRenderer;

    public GroundItemRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
        this.shadowRadius = 0.15f;
    }

    @Override
    public void render(GroundItemEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        if (!entity.hasStack()) return;

        ItemStack stack = entity.stack();

        poseStack.pushPose();

        poseStack.translate(0.0, LIFT_BLOCKS, 0.0);
        // Поворот: Y завжди 0, X — випадковий, фіксований.
        poseStack.mulPose(Axis.YP.rotationDegrees(GroundItemEntity.ROTATION_Y));
        poseStack.mulPose(Axis.XP.rotationDegrees(entity.rotationX()));
        poseStack.scale(SCALE, SCALE, SCALE);

        BakedModel model = itemRenderer.getModel(stack, entity.level(), null, entity.getId());
        itemRenderer.render(stack, ItemDisplayContext.HEAD, false, poseStack, buffers,
            packedLight, OverlayTexture.NO_OVERLAY, model);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(GroundItemEntity entity) {
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }
}
