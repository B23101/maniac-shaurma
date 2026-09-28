package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.ElectricWireMountEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Рендерер кріплення електричного дроту — з АВТОМАТИЧНИМ поворотом під
 * напрямок встановлення (ТЗ 2.3: «модель кріплення повинна автоматично
 * повертатися»).
 *
 * ── Звідки береться кут ──────────────────────────────────────────────
 * Напрямок рахує СЕРВЕР при створенні й клацає у синхронізоване поле
 * сутності ({@code ElectricWireMountEntity.yawDegrees()}). Тут ми лише
 * перетворюємо його на кут навколо вертикальної осі. Модель за
 * замовчуванням дивиться на ПІВНІЧ ({@code get2DDataValue() == 2} → 0°),
 * тож віднімаємо північ і множимо на 90°.
 *
 * ── Чому вручну, а не через {@code entityYaw} ────────────────────────
 * Та сама причина, що в {@link GeneratorRenderer}: для НЕ-Living
 * сутностей GeckoLib 4.x обертає модель за реальним
 * {@code entity.getYRot()}, а не за переданим {@code entityYaw}. Ми
 * тримаємо {@code getYRot()} = 0, тож поворот накладаємо самі на
 * {@code PoseStack}, а рушію віддаємо {@code 0}, щоб кут не подвоївся.
 */
@OnlyIn(Dist.CLIENT)
public class ElectricWireMountRenderer extends GeoEntityRenderer<ElectricWireMountEntity> {

    public ElectricWireMountRenderer(EntityRendererProvider.Context context) {
        super(context, new ElectricWireMountGeoModel());
        this.shadowRadius = 0.25f;
    }

    @Override
    public void render(ElectricWireMountEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        // Сервер дає точний кут уздовж дроту (0° = північ, схід = +90°) —
        // працює і на діагоналях. Збігається зі старою схемою
        // (NORTH→0°, EAST→+90°), яка крутила модель тим самим Axis.YP.
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(entity.yawDegrees()));
        super.render(entity, 0f, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }
}
