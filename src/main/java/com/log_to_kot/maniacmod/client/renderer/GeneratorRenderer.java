package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.GeneratorEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Рендерер сутності генератора.
 *
 * ── Поворот ──────────────────────────────────────────────────────────
 * Генератор не має "погляду" — у нього є лише орієнтація на місці
 * ({@link GeneratorEntity#rotationX()}, попри назву — це кут навколо
 * ВЕРТИКАЛЬНОЇ осі, той самий сенс, що {@code GroundItemEntity}
 * використовує для випадкового повороту предметів на землі; назва
 * лишена для узгодженості з рештою поля, де "X" означає "кут, не
 * пов'язаний з рухом").
 *
 * <p><b>БАГФІКС: модель завжди малювалась під кутом 0°, хоча
 * {@link GeneratorEntity#rotationX()} справді зберігав і синхронізував
 * випадкове значення.</b> Причина — у самому GeckoLib 4.x: для сутностей,
 * що НЕ є {@code LivingEntity} (як {@link GeneratorEntity}, що прямо
 * успадковує {@code Entity}), {@code GeoEntityRenderer} обертає модель за
 * РЕАЛЬНИМ {@code entity.getYRot()}, а не за параметром {@code entityYaw},
 * переданим у {@link #render}. Ми свідомо тримаємо {@code getYRot()}
 * сутності на {@link GeneratorEntity#ROTATION_Y} (завжди 0 — генератор не
 * обертається сам собою і не має "погляду"), тож колишня передача
 * {@code entity.rotationX()} як {@code entityYaw} рушію нічого не
 * змінювала: рушій її просто ігнорував для не-Living сутностей.</p>
 *
 * <p>Виправлення — той самий прийом, що вже застосовує
 * {@code GroundItemRenderer} для предметів на землі: обертаємо
 * {@link PoseStack} ВРУЧНУ навколо вертикальної осі перед тим, як
 * віддати керування {@code GeoEntityRenderer}, а самому рушію передаємо
 * {@code entityYaw = 0}, щоб він не накладав СВІЙ (як з'ясовано — завжди
 * нульовий для цієї сутності) поворот поверх нашого й кут не подвоївся.
 * Обертання — навколо {@code (0, y, 0)} без додаткового зсуву: на момент
 * виклику {@link #render} {@code poseStack} уже транслятований у точку
 * сутності (як завжди в {@code EntityRenderer}), а вона й так центр
 * хітбокса 1.2×1.2 по X/Z (див. {@code ModEntityTypes.GENERATOR}) —
 * зайвий зсув на пів блока обертав би модель не навколо її середини.</p>
 */
@OnlyIn(Dist.CLIENT)
public class GeneratorRenderer extends GeoEntityRenderer<GeneratorEntity> {

    public GeneratorRenderer(EntityRendererProvider.Context context) {
        super(context, new GeneratorGeoModel());
        this.shadowRadius = 0.6f;
    }

    @Override
    public void render(GeneratorEntity entity, float entityYaw, float partialTick,
                        PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(entity.rotationX()));
        // entityYaw = 0: поворот уже застосовано вручну вище; передавати
        // rotationX() ще й сюди означало б покладатись на те саме
        // (недіюче для не-Living сутностей) поле, яке й спричинило баг.
        super.render(entity, 0f, partialTick, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }
}
