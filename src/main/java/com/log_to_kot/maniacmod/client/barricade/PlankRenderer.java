package com.log_to_kot.maniacmod.client.barricade;

import com.log_to_kot.maniacmod.barricade.BarricadeGeometry;
import com.log_to_kot.maniacmod.barricade.BoardSpec;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Малює одну дошку як тонкий брусок із текстурою довільного блока. ЄДИНЕ місце, що знає, як
 * виглядає дошка: його викликають і рендерер зареєстрованої барикади, і буфер/прев'ю режиму
 * будування — тому те, що адмін бачив при будуванні, у грі виглядає ТОЧНО так само.
 *
 * <p>Текстура береться з атласу блоків (іконка частинок блока). Атлас не тайлиться, тож довга
 * дошка ріжеться на відрізки ≤ 1 блока, кожен із яких показує спрайт цілком; зсув по ширині
 * залежить від положення дошки — сусідні дошки не виглядають копіями.
 */
@OnlyIn(Dist.CLIENT)
public final class PlankRenderer {

    private static final double SEGMENT = 1.0;

    private PlankRenderer() {}

    public static TextureAtlasSprite sprite(ResourceLocation blockId) {
        Block block = ForgeRegistries.BLOCKS.getValue(blockId);
        BlockState state = block == null || block == Blocks.AIR
            ? Blocks.OAK_PLANKS.defaultBlockState() : block.defaultBlockState();
        return Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
    }

    /**
     * @param origin початок координат поточного PoseStack у світових координатах (позиція сутності
     *               або камери)
     */
    public static void draw(PoseStack pose, VertexConsumer vc, BoardSpec spec, Vec3 origin,
                            int light, float alpha) {
        draw(pose, vc, spec, spec.a(), origin, light, alpha);
    }

    /**
     * @param grainKey точка, від якої залежить зсув текстури по ширині. Для дошки, що рухається
     *                 (провисає, падає), це ЗАВЖДИ початкова {@code a} — інакше текстура мерехтіла б.
     */
    public static void draw(PoseStack pose, VertexConsumer vc, BoardSpec spec, Vec3 grainKey, Vec3 origin,
                            int light, float alpha) {
        BarricadeGeometry.Frame f = BarricadeGeometry.frame(spec);
        if (f == null) return;
        TextureAtlasSprite sprite = sprite(spec.block());
        Matrix4f m = pose.last().pose();
        Matrix3f nm = pose.last().normal();
        Vec3 c = f.center().subtract(origin);

        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), dv = sprite.getV1() - sprite.getV0();
        float vOff = (Math.abs(grainKey.hashCode()) % 8) / 8f * 0.6f;
        float vA = v0 + vOff * dv;
        float vWide = vA + (float) BarricadeGeometry.WIDTH * dv;
        float vThin = vA + (float) BarricadeGeometry.THICKNESS * dv;

        double total = f.halfLength() * 2.0;
        int segments = Math.max(1, (int) Math.ceil(total / SEGMENT));
        double segLen = total / segments;
        double hw = f.halfWidth();
        double ht = f.halfThickness();

        for (int i = 0; i < segments; i++) {
            double s0 = -f.halfLength() + i * segLen;
            double s1 = s0 + segLen;

            // Широкі грані (±n).
            quad(vc, m, nm, pt(c, f, s0, -hw, ht), pt(c, f, s1, -hw, ht), pt(c, f, s1, hw, ht), pt(c, f, s0, hw, ht),
                f.n(), u0, vA, u1, vWide, light, alpha);
            quad(vc, m, nm, pt(c, f, s0, hw, -ht), pt(c, f, s1, hw, -ht), pt(c, f, s1, -hw, -ht), pt(c, f, s0, -hw, -ht),
                f.n().scale(-1), u0, vA, u1, vWide, light, alpha);
            // Довгі торці (±u).
            quad(vc, m, nm, pt(c, f, s0, hw, -ht), pt(c, f, s1, hw, -ht), pt(c, f, s1, hw, ht), pt(c, f, s0, hw, ht),
                f.u(), u0, vA, u1, vThin, light, alpha);
            quad(vc, m, nm, pt(c, f, s0, -hw, ht), pt(c, f, s1, -hw, ht), pt(c, f, s1, -hw, -ht), pt(c, f, s0, -hw, -ht),
                f.u().scale(-1), u0, vA, u1, vThin, light, alpha);
        }
    }

    private static Vec3 pt(Vec3 center, BarricadeGeometry.Frame f, double along, double across, double thick) {
        return center.add(f.dir().scale(along)).add(f.u().scale(across)).add(f.n().scale(thick));
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Matrix3f nm,
                             Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 n,
                             float u0, float v0, float u1, float v1, int light, float alpha) {
        vertex(vc, m, nm, p0, n, u0, v0, light, alpha);
        vertex(vc, m, nm, p1, n, u1, v0, light, alpha);
        vertex(vc, m, nm, p2, n, u1, v1, light, alpha);
        vertex(vc, m, nm, p3, n, u0, v1, light, alpha);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Matrix3f nm, Vec3 p, Vec3 n,
                               float u, float v, int light, float alpha) {
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z)
            .color(1f, 1f, 1f, alpha)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(light)
            .normal(nm, (float) n.x, (float) n.y, (float) n.z)
            .endVertex();
    }
}
