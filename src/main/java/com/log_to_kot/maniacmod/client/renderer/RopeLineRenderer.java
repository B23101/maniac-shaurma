package com.log_to_kot.maniacmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Малює ПРЯМУ vertex-стрічку («мотузку») між двома точками — той самий
 * підхід, що {@code ZiplineAnchorBlockEntityRenderer.renderWire} у
 * snipers_shaurma (переніс із {@code HyphalConductorBlockEntityRenderer}),
 * але БЕЗ провисання: {@code sagAmount} там існує, щоб трос зіплайна
 * провисав під вагою, а дріт-пастка за ТЗ 2.1 мусить лишатись рівним,
 * тому тут інтерпольована точка — це проста лінійна інтерполяція
 * {@code start → end}, без квадратичної/синусоїдної добавки по Y.
 * <p>
 * Колір ЧОРНИЙ (майже без відтінку): дріт — це ізольований кабель, а не
 * кольорова лінія-індикатор і не бурштиновий «під напругу». Чистий
 * (0,0,0) не беремо — на дуже темному тлі стрічка зникала б повністю,
 * тож лишається ледь помітний холодний відтінок.
 * <p>
 * {@link RenderType#leash()} — той самий ванільний тип, що й у зіплайна:
 * стабільний, без власних шейдерів чи текстур, коректно тіньований по
 * light-у обох кінців.
 */
public final class RopeLineRenderer {

    /** Чорний кабель. Правиться лише тут — колір спільний для дроту й прев'ю розміщення. */
    public static final float COLOR_R = 0.03f, COLOR_G = 0.03f, COLOR_B = 0.035f;

    /** Товщина стрічки. У зіплайна 0.09 — для дроту-розтяжки це надто масивно, тому тонше (правиться лише тут). */
    private static final float SCALE = 0.03F;

    private RopeLineRenderer() {}

    /**
     * @param poseStack   у координатах ПОЧАТКОВОЇ точки (0,0,0) = {@code from}
     * @param bufferSource куди писати вершини
     * @param relEnd      {@code to - from} у локальних координатах
     * @param startLight  packed light у {@code from}
     * @param endLight    packed light у {@code to}
     * @param alpha       непрозорість (1.0 — суцільна лінія; менше — для прев'ю розміщення)
     */
    public static void render(PoseStack poseStack, MultiBufferSource bufferSource, Vec3 relEnd,
                              int startLight, int endLight, float alpha) {
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.leash());
        renderWire(poseStack, consumer, relEnd, startLight, endLight, alpha);
    }

    private static void renderWire(PoseStack poseStack, VertexConsumer vertexConsumer, Vec3 relEnd,
                                   int startLight, int endLight, float alpha) {
        poseStack.pushPose();

        float relX = (float) relEnd.x;
        float relY = (float) relEnd.y;
        float relZ = (float) relEnd.z;

        float normaliseAndScale = Mth.invSqrt(relX * relX + relZ * relZ) * SCALE / 2.0F;
        float dx = relZ * normaliseAndScale;
        float dz = relX * normaliseAndScale;

        boolean applyVerticalFix = Math.abs(relX) < 0.001 && Math.abs(relZ) < 0.001;
        if (applyVerticalFix) {
            dx = SCALE / 2.0F;
            dz = SCALE / 2.0F;
        }

        Matrix4f matrix4f = poseStack.last().pose();

        // Один сегмент вистачає — дріт РІВНИЙ (без провисання), тож
        // проміжні точки нічим не відрізнялись би від лінійної
        // інтерполяції кінців. Менше вершин, ніж у зіплайна, де сегменти
        // потрібні саме заради катенари.
        int segments = 1;

        addVertexPair(vertexConsumer, matrix4f, 0, 0, 0, startLight, startLight, 0.0F, 0.0F, 0.0F, 0.0F, 0, segments, alpha);
        for (int i = 0; i <= segments; i++) {
            addVertexPair(vertexConsumer, matrix4f, relX, relY, relZ, startLight, endLight, SCALE, SCALE, dx, dz, i, segments, alpha);
        }
        addVertexPair(vertexConsumer, matrix4f, relX, relY, relZ, endLight, endLight, 0.0F, 0.0F, 0.0F, 0.0F, segments, segments, alpha);

        if (applyVerticalFix) {
            dx = SCALE / 2.0F;
            dz = -SCALE / 2.0F;
        }
        for (int i = segments; i >= 0; i--) {
            addVertexPair(vertexConsumer, matrix4f, relX, relY, relZ, startLight, endLight, SCALE, 0.0F, dx, dz, i, segments, alpha);
        }
        addVertexPair(vertexConsumer, matrix4f, 0, 0, 0, startLight, startLight, 0.0F, 0.0F, 0.0F, 0.0F, 0, segments, alpha);

        poseStack.popPose();
    }

    private static void addVertexPair(VertexConsumer buffer, Matrix4f pose, float startX, float startY, float startZ,
                                      int startLight, int endLight, float yOffset, float dy, float dx, float dz,
                                      int index, int segments, float alpha) {
        float lerpFactor = index / (float) segments;

        int lerpedLight = (int) Mth.lerp(lerpFactor, (float) (startLight & 0xFFFF), (float) (endLight & 0xFFFF));
        int lerpedSkyLight = (int) Mth.lerp(lerpFactor, (float) (startLight >> 16 & 0xFFFF), (float) (endLight >> 16 & 0xFFFF));
        int light = lerpedLight | (lerpedSkyLight << 16);

        // Пряма лінійна інтерполяція, БЕЗ провисання по Y — дріт рівний
        // (ТЗ 2.1), на відміну від катенари зіплайна.
        float lerpedX = startX * lerpFactor;
        float lerpedY = startY * lerpFactor;
        float lerpedZ = startZ * lerpFactor;

        buffer.vertex(pose, lerpedX - dx, lerpedY - 0.5F * yOffset + dy, lerpedZ + dz)
                .color(COLOR_R, COLOR_G, COLOR_B, alpha).uv2(light).endVertex();
        buffer.vertex(pose, lerpedX + dx, lerpedY + 0.5F * yOffset - dy, lerpedZ - dz)
                .color(COLOR_R, COLOR_G, COLOR_B, alpha).uv2(light).endVertex();
    }
}
