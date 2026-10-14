package com.log_to_kot.maniacmod.client.barricade;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Дрібні GUI-примітиви з дробовими координатами: прямокутники, багатокутники, диски, дуги,
 * лінії та тайлована текстурована дошка. Ванільний {@code GuiGraphics.fill} працює лише з цілими
 * пікселями й без градієнтів по вершинах, а дошки повертаються на довільний кут — тому тут усе
 * малюється власними квадами у поточній матриці {@code g.pose()} (тож працюють і поворот, і
 * масштаб, накладені зовні).
 */
@OnlyIn(Dist.CLIENT)
final class GuiShapes {

    private GuiShapes() {}

    private static int a(int argb) { return (argb >>> 24) & 0xFF; }
    private static int r(int argb) { return (argb >>> 16) & 0xFF; }
    private static int gr(int argb) { return (argb >>> 8) & 0xFF; }
    private static int b(int argb) { return argb & 0xFF; }

    static int withAlpha(int argb, float alpha) {
        int na = Math.round(a(argb) * Math.max(0f, Math.min(1f, alpha)));
        return (na << 24) | (argb & 0x00FFFFFF);
    }

    static int mix(int c0, int c1, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        return (Math.round(a(c0) + (a(c1) - a(c0)) * k) << 24)
            | (Math.round(r(c0) + (r(c1) - r(c0)) * k) << 16)
            | (Math.round(gr(c0) + (gr(c1) - gr(c0)) * k) << 8)
            | Math.round(b(c0) + (b(c1) - b(c0)) * k);
    }

    // ── Кольорові фігури ─────────────────────────────────────────────────

    private static BufferBuilder beginColor(VertexFormat.Mode mode) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(mode, DefaultVertexFormat.POSITION_COLOR);
        return bb;
    }

    private static void v(BufferBuilder bb, Matrix4f m, float x, float y, int argb) {
        bb.vertex(m, x, y, 0f).color(r(argb), gr(argb), b(argb), a(argb)).endVertex();
    }

    private static void end(BufferBuilder bb) {
        BufferUploader.drawWithShader(bb.end());
        RenderSystem.disableBlend();
    }

    /** Прямокутник із дробовими координатами. */
    static void rect(GuiGraphics g, float x0, float y0, float x1, float y1, int argb) {
        gradientRect(g, x0, y0, x1, y1, argb, argb, argb, argb);
    }

    /** Прямокутник із кольором у кожному куті (за годинниковою від лівого верхнього). */
    static void gradientRect(GuiGraphics g, float x0, float y0, float x1, float y1,
                             int cTl, int cTr, int cBr, int cBl) {
        Matrix4f m = g.pose().last().pose();
        BufferBuilder bb = beginColor(VertexFormat.Mode.QUADS);
        v(bb, m, x0, y0, cTl);
        v(bb, m, x0, y1, cBl);
        v(bb, m, x1, y1, cBr);
        v(bb, m, x1, y0, cTr);
        end(bb);
    }

    /** Опуклий багатокутник віялом від центру: центр і краї можуть мати різний колір (градієнт). */
    static void fan(GuiGraphics g, float cx, float cy, int centerArgb, List<float[]> pts, int edgeArgb) {
        if (pts.size() < 3) return;
        Matrix4f m = g.pose().last().pose();
        BufferBuilder bb = beginColor(VertexFormat.Mode.TRIANGLE_FAN);
        v(bb, m, cx, cy, centerArgb);
        for (float[] p : pts) v(bb, m, p[0], p[1], edgeArgb);
        v(bb, m, pts.get(0)[0], pts.get(0)[1], edgeArgb);
        end(bb);
    }

    /** Диск із градієнтом від центру до краю. */
    static void disk(GuiGraphics g, float cx, float cy, float radius, int centerArgb, int edgeArgb) {
        int seg = Math.max(10, Math.min(28, Math.round(radius * 2.2f)));
        Matrix4f m = g.pose().last().pose();
        BufferBuilder bb = beginColor(VertexFormat.Mode.TRIANGLE_FAN);
        v(bb, m, cx, cy, centerArgb);
        for (int i = 0; i <= seg; i++) {
            double ang = Math.PI * 2 * i / seg;
            v(bb, m, cx + (float) Math.cos(ang) * radius, cy + (float) Math.sin(ang) * radius, edgeArgb);
        }
        end(bb);
    }

    /** Лінія товщини {@code thick} із заокругленням не робимо — лише прямий квад. */
    static void line(GuiGraphics g, float x0, float y0, float x1, float y1, float thick, int argb) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1e-4f) return;
        float nx = -dy / len * thick / 2f, ny = dx / len * thick / 2f;
        Matrix4f m = g.pose().last().pose();
        BufferBuilder bb = beginColor(VertexFormat.Mode.QUADS);
        v(bb, m, x0 + nx, y0 + ny, argb);
        v(bb, m, x1 + nx, y1 + ny, argb);
        v(bb, m, x1 - nx, y1 - ny, argb);
        v(bb, m, x0 - nx, y0 - ny, argb);
        end(bb);
    }

    /** Дуга-кільце: від 12 години за годинниковою, частка {@code frac} (0..1). */
    static void ring(GuiGraphics g, float cx, float cy, float radius, float thick, float frac, int argb) {
        if (frac <= 0f) return;
        float f = Math.min(1f, frac);
        int seg = Math.max(8, Math.round(48 * f));
        float ri = radius - thick / 2f, ro = radius + thick / 2f;
        Matrix4f m = g.pose().last().pose();
        BufferBuilder bb = beginColor(VertexFormat.Mode.TRIANGLE_STRIP);
        for (int i = 0; i <= seg; i++) {
            double ang = -Math.PI / 2 + Math.PI * 2 * f * i / seg;
            float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
            v(bb, m, cx + c * ro, cy + s * ro, argb);
            v(bb, m, cx + c * ri, cy + s * ri, argb);
        }
        end(bb);
    }

    // ── Текстурована дошка ───────────────────────────────────────────────

    /**
     * Малює дошку з центром у (0,0) ЛОКАЛЬНОЇ системи (поточної матриці): довжина вздовж X, ширина
     * вздовж Y. Текстура з атласу блоків лягає так само, як у світі ({@code PlankRenderer}): один
     * спрайт на блок довжини, по ширині — відповідна частка спрайта зі зсувом {@code grainOffset}.
     *
     * @param pxPerBlock скільки пікселів у блоці (визначає розмір «клітинки» текстури)
     * @param across     текстура ПОПЕРЕК довжини (діагональні планки фіксованої барикади)
     * @param tint       множник кольору ARGB (альфа — прозорість, RGB — затемнення/підсвітка)
     */
    static void plankTexture(GuiGraphics g, TextureAtlasSprite sprite, float length, float width,
                             float pxPerBlock, double grainOffset, boolean across, int tint) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        Matrix4f m = g.pose().last().pose();

        float u0 = sprite.getU0(), u1 = sprite.getU1();
        float v0 = sprite.getV0(), v1 = sprite.getV1();
        float du = u1 - u0, dv = v1 - v0;
        // Невеличкий відступ усередину спрайта: без нього сусідній спрайт атласу «підсвічує» край.
        float eu = du * 0.002f, ev = dv * 0.002f;

        float widthBlocks = width / pxPerBlock;
        float wFrac = Math.min(1f, widthBlocks);
        float off = (float) Math.min(1.0 - wFrac, Math.max(0.0, grainOffset));

        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float x = -length / 2f;
        float y0 = -width / 2f, y1 = width / 2f;
        int cr = r(tint), cg = gr(tint), cb = b(tint), ca = a(tint);
        while (x < length / 2f - 1e-3f) {
            float seg = Math.min(pxPerBlock, length / 2f - x);
            float frac = Math.max(0.01f, seg / pxPerBlock);
            float xe = x + seg;
            // «Вздовж» дошки текстура береться на частку frac одного спрайта, «впоперек» — на частку
            // ширини зі зсувом off. Для across осі U/V міняються місцями (як у моделі діагоналі).
            float lenA = (across ? v0 : u0) + eu;
            float lenB = (across ? v0 : u0) + (across ? dv : du) * frac - eu;
            float widA = (across ? u0 : v0) + (across ? du : dv) * off + (across ? eu : ev);
            float widB = (across ? u0 : v0) + (across ? du : dv) * (off + wFrac) - (across ? eu : ev);
            float[][] pos = { {x, y0}, {xe, y0}, {xe, y1}, {x, y1} };
            float[] along = { lenA, lenB, lenB, lenA };
            float[] across_ = { widA, widA, widB, widB };
            for (int i = 0; i < 4; i++) {
                float uu = across ? across_[i] : along[i];
                float vv = across ? along[i] : across_[i];
                bb.vertex(m, pos[i][0], pos[i][1], 0f).uv(uu, vv).color(cr, cg, cb, ca).endVertex();
            }
            x = xe;
        }
        BufferUploader.drawWithShader(bb.end());
        RenderSystem.disableBlend();
    }

    /** Малий квадратик зі шматком текстури (тріска): центр, розмір, поворот навколо центру. */
    static void chip(GuiGraphics g, TextureAtlasSprite sprite, float cx, float cy, float size, float rot,
                     float uFrac, float vFrac, float alpha) {
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0f);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotation(rot));
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        Matrix4f m = g.pose().last().pose();
        float du = sprite.getU1() - sprite.getU0(), dv = sprite.getV1() - sprite.getV0();
        float uA = sprite.getU0() + du * Math.min(0.75f, uFrac), uB = uA + du * 0.25f;
        float vA = sprite.getV0() + dv * Math.min(0.75f, vFrac), vB = vA + dv * 0.25f;
        float h = size / 2f;
        int al = Math.round(255 * Math.max(0f, Math.min(1f, alpha)));
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.vertex(m, -h, -h, 0f).uv(uA, vA).color(255, 255, 255, al).endVertex();
        bb.vertex(m, -h, h, 0f).uv(uA, vB).color(255, 255, 255, al).endVertex();
        bb.vertex(m, h, h, 0f).uv(uB, vB).color(255, 255, 255, al).endVertex();
        bb.vertex(m, h, -h, 0f).uv(uB, vA).color(255, 255, 255, al).endVertex();
        BufferUploader.drawWithShader(bb.end());
        RenderSystem.disableBlend();
        g.pose().popPose();
    }
}
