package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.GeneratorEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Рендерер сутності генератора.
 *
 * ── Поворот ──────────────────────────────────────────────────────────
 * Стандартний {@link GeoEntityRenderer} обертає модель за яв ("yaw")
 * сутності, який зазвичай означає "куди дивиться" — але в генератора
 * немає "погляду", у нього є лише лежача орієнтація по осі X
 * ({@link GeneratorEntity#rotationX()}), той самий підхід, що
 * {@code GroundItemRenderer} застосовує для лежачих предметів.
 * Замість переозброєння всієї матриці рендеру (що для {@code GeoEntityRenderer}
 * означало б переписувати внутрішній {@code renderRecursively}, який
 * GeckoLib 4.x будує з власних {@code GeoBone}-трансформацій),
 * найпростіший робочий спосіб — передати {@code rotationX()} рушію як
 * "entityYaw" параметр {@link #render}: {@code GeoEntityRenderer} однаково
 * лише повертає навколо вертикалі за цим кутом у своєму базовому проході,
 * а сама "лежача" поза (правильна вісь нахилу) вже закладається в саму
 * .geo.json модель художником (корінь моделі орієнтований лежачи, а не
 * стоячи) — так само, як у {@code GroundItemRenderer} нахил по X
 * реалізовано прямим поворотом PoseStack, тут це буде повністю
 * реалізовано після появи фінальної моделі; поточна версія — робочий
 * каркас під конвенцію GeckoLib, а не остаточна геометрія повороту.
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
        super.render(entity, entity.rotationX(), partialTick, poseStack, bufferSource, packedLight);
    }
}
