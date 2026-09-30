package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.ElectricWireEntity;
import com.log_to_kot.maniacmod.traps.ElectricWireArchetype;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Кастомний рендер електричного дроту (ТЗ 2.3): ПРЯМИЙ (без провисання)
 * відрізок між двома кріпленнями, довжина й напрямок — динамічні.
 *
 * ── Принцип ──────────────────────────────────────────────────────────
 * Той самий підхід, що рендер каната зіплайна в {@code snipers_shaurma}
 * ({@code ZiplineAnchorBlockEntityRenderer}): трос малюється НЕ як блок і
 * не як фіксована модель, а як vertex-стрічка, що рахується від двох
 * точок кожної миті ({@link RopeLineRenderer}, переніс того самого
 * {@code renderWire}). Різниця: зіплайн додає КАТЕНАРУ (провисання), а
 * дріт за ТЗ мусить бути РІВНИМ — {@link RopeLineRenderer} інтерполює
 * кінці лінійно, без жодного згину.
 *
 * ── Чому НЕ {@code renderLineBox} (стара версія) ────────────────────
 * {@code LevelRenderer.renderLineBox} малює РАМКУ AABB (усі 12 ребер
 * bounding-боксу навколо відрізка) — на прямому дроті це виглядало як
 * прямокутник/квадрат, а не як лінія, і ставало особливо помітним по
 * діагоналі. Справжня «мотузка» — це стрічка вздовж САМОГО відрізка
 * {@code a→b}, а не контур його bounding-боксу.
 *
 * ── Колір ────────────────────────────────────────────────────────────
 * Чорний (див. {@link RopeLineRenderer#COLOR_R}): ізольований кабель, без
 * бурштинового «під напругу» забарвлення.
 */
@OnlyIn(Dist.CLIENT)
public class ElectricWireRenderer extends EntityRenderer<ElectricWireEntity> {

    public ElectricWireRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0f;
    }

    @Override
    public void render(ElectricWireEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        Vec3 origin = entity.position();
        Vec3 a = ElectricWireArchetype.wireAnchor(entity.level(), entity.pointA()).subtract(origin);
        Vec3 b = ElectricWireArchetype.wireAnchor(entity.level(), entity.pointB()).subtract(origin);

        int lightA = LevelRenderer.getLightColor(entity.level(), entity.pointA().above());
        int lightB = LevelRenderer.getLightColor(entity.level(), entity.pointB().above());

        poseStack.pushPose();
        poseStack.translate(a.x, a.y, a.z);
        RopeLineRenderer.render(poseStack, bufferSource, b.subtract(a), lightA, lightB, 1.0f);
        poseStack.popPose();
        // endBatch тут НЕ викликаємо: EntityRenderDispatcher дозавершує
        // батчі після проходу; ручний flush посеред рендеру сутностей
        // ламав би порядок із рештою рендерерів.
    }

    @Override
    public ResourceLocation getTextureLocation(ElectricWireEntity entity) {
        // RenderType.leash() не текстурований (плоский колір), але метод
        // зобов'язаний щось повернути — дублюємо ваніль (LeashKnotRenderer)
        // і беремо текстуру світу як заглушку.
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }
}
