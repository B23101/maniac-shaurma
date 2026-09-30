package com.log_to_kot.maniacmod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * ОБ'ЄМНА нитка між двома точками — чотиригранна призма з ТЕКСТУРОЮ.
 *
 * На відміну від {@link RopeLineRenderer} (пласка кольорова стрічка
 * {@code RenderType.leash()}, як розтяжка), тут справжній об'єм: чотири
 * грані навколо осі, кожна з тайлінгом текстури вздовж довжини. Тому нитка
 * виглядає витою мотузкою з будь-якого боку й освітлюється нормалями.
 * Малюється в координатах {@code poseStack}: {@code from}/{@code to} — точки
 * відносно поточного початку.
 */
@OnlyIn(Dist.CLIENT)
public final class ThreadRenderer {

    /** Скільки блоків нитки припадає на один повтор текстури по довжині. */
    private static final double TILE_BLOCKS = 0.15;

    private ThreadRenderer() {}

    public static void render(PoseStack poseStack, MultiBufferSource buffers, ResourceLocation texture,
                              Vec3 from, Vec3 to, float thickness, int light) {
        Vec3 dir = to.subtract(from);
        double len = dir.length();
        if (len < 1.0E-3) return;
        Vec3 d = dir.scale(1.0 / len);

        // Базис, перпендикулярний осі нитки (працює й для майже вертикальної).
        Vec3 ref = Math.abs(d.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = d.cross(ref).normalize();
        Vec3 v = d.cross(u).normalize();
        double h = thickness / 2.0;

        Vec3[] c = {
            u.scale(h).add(v.scale(h)),
            u.scale(-h).add(v.scale(h)),
            u.scale(-h).add(v.scale(-h)),
            u.scale(h).add(v.scale(-h))
        };

        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();
        float vEnd = (float) (len / TILE_BLOCKS);

        for (int i = 0; i < 4; i++) {
            Vec3 a = c[i];
            Vec3 b = c[(i + 1) & 3];
            Vec3 n = a.add(b).normalize();
            vertex(vc, pose, normal, from.add(a), 0f, 0f, light, n);
            vertex(vc, pose, normal, from.add(b), 1f, 0f, light, n);
            vertex(vc, pose, normal, to.add(b),   1f, vEnd, light, n);
            vertex(vc, pose, normal, to.add(a),   0f, vEnd, light, n);
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f pose, Matrix3f normal, Vec3 p,
                               float u, float v, int light, Vec3 n) {
        vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z)
            .color(255, 255, 255, 255)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(light)
            .normal(normal, (float) n.x, (float) n.y, (float) n.z)
            .endVertex();
    }
}
