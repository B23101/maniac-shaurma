package com.log_to_kot.maniacmod.client.style;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.util.function.IntUnaryOperator;

/**
 * Гладкі кола, дуги й сектори для GUI — справжня геометрія (трикутні віяла й
 * смуги), а не піксельні {@code fill}.
 *
 * ── Навіщо ───────────────────────────────────────────────────────────
 * Коло, намальоване {@code GuiGraphics#fill} піксель за пікселем, має
 * «сходинки» по краю й не вміє градієнтів. Тут кожна фігура — це
 * {@link Tesselator}-батч із кольором у вершинах: радіальні/кутові градієнти
 * альфи, м'які краї (світіння), кутові сектори для «годинникової» перезарядки.
 *
 * ── Правила ──────────────────────────────────────────────────────────
 *   • Кути в радіанах; 0 — праворуч, додатний напрямок — ПО ГОДИННИКОВІЙ
 *     (вісь Y екрана вниз). {@link #TOP} = -π/2 — «12 годин».
 *   • Перед кожним батчем викликається {@code g.flush()}, щоб порядок малювання
 *     зі звичайними {@code fill}/текстурами лишився коректним.
 *   • Множник кольору ({@code RenderSystem.setShaderColor}) НЕ чіпається —
 *     загальна прозорість оверлея, виставлена викликачем, діє й тут.
 *   • Кольори — ARGB; альфа у вершинах інтерполюється.
 */
public final class CircleFx {

    public static final float TAU = (float) (Math.PI * 2.0);
    public static final float TOP = -Mth.HALF_PI;

    private CircleFx() {}

    // ── Внутрішнє ────────────────────────────────────────────────────────

    private static BufferBuilder begin(GuiGraphics g, VertexFormat.Mode mode) {
        g.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(mode, DefaultVertexFormat.POSITION_COLOR);
        return b;
    }

    private static void end() {
        Tesselator.getInstance().end();
        RenderSystem.enableCull();
    }

    private static void v(BufferBuilder b, Matrix4f m, float x, float y, int argb) {
        b.vertex(m, x, y, 0f)
            .color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF)
            .endVertex();
    }

    private static int steps(float radius, float sweep) {
        int full = Mth.clamp(Math.round(radius * 2.4f), 28, 180);
        return Math.max(1, (int) Math.ceil(full * Math.abs(sweep) / TAU));
    }

    // ── Фігури ───────────────────────────────────────────────────────────

    /** Суцільний диск із радіальним градієнтом: {@code centerArgb} у центрі → {@code edgeArgb} на краю. */
    public static void disc(GuiGraphics g, float cx, float cy, float r, int centerArgb, int edgeArgb) {
        sector(g, cx, cy, r, 0f, TAU, centerArgb, edgeArgb);
    }

    /** Сектор диска від {@code start} на {@code sweep} рад. (по годинниковій при sweep &gt; 0). */
    public static void sector(GuiGraphics g, float cx, float cy, float r, float start, float sweep,
                              int centerArgb, int edgeArgb) {
        if (r <= 0f || Math.abs(sweep) < 1e-4f) return;
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = begin(g, VertexFormat.Mode.TRIANGLE_FAN);
        v(b, m, cx, cy, centerArgb);
        int n = steps(r, sweep);
        for (int i = 0; i <= n; i++) {
            float a = start + sweep * i / n;
            v(b, m, cx + Mth.cos(a) * r, cy + Mth.sin(a) * r, edgeArgb);
        }
        end();
    }

    /**
     * Дуга-смуга між радіусами {@code rIn}..{@code rOut}. Колір плавно іде від
     * {@code colStart} (на {@code start}) до {@code colEnd} (на {@code start+sweep}) —
     * саме так робиться «комета» з хвостом. {@code innerFade}/{@code outerFade}
     * множать альфу на внутрішньому/зовнішньому краї (0 — м'яке згасання, 1 — чіткий край).
     */
    public static void arc(GuiGraphics g, float cx, float cy, float rIn, float rOut,
                           float start, float sweep, int colStart, int colEnd,
                           float innerFade, float outerFade) {
        if (rOut <= rIn || Math.abs(sweep) < 1e-4f) return;
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = begin(g, VertexFormat.Mode.TRIANGLE_STRIP);
        int n = steps(rOut, sweep);
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            float a = start + sweep * t;
            int c = GeneratorFuelUiTheme.mix(colStart, colEnd, t);
            float cos = Mth.cos(a), sin = Mth.sin(a);
            v(b, m, cx + cos * rIn, cy + sin * rIn, GeneratorFuelUiTheme.alpha(c, innerFade));
            v(b, m, cx + cos * rOut, cy + sin * rOut, GeneratorFuelUiTheme.alpha(c, outerFade));
        }
        end();
    }

    /** Повне кільце одного кольору з чіткими краями. */
    public static void ring(GuiGraphics g, float cx, float cy, float rIn, float rOut, int argb) {
        arc(g, cx, cy, rIn, rOut, 0f, TAU, argb, argb, 1f, 1f);
    }

    /** М'яке світіння ЗОВНІ кола радіуса {@code r}: альфа від {@code argb} до 0 за {@code width} px. */
    public static void glow(GuiGraphics g, float cx, float cy, float r, float width, int argb) {
        arc(g, cx, cy, r, r + width, 0f, TAU, argb, argb, 1f, 0f);
    }

    /**
     * Кільце рисок (тіків) між {@code rIn}..{@code rOut}. Колір кожної риски
     * віддає {@code colorOf(index)} (ARGB, альфа 0 — риска пропускається).
     * Весь батч — один виклик, тож дешево навіть при 60+ рисках.
     */
    public static void ticks(GuiGraphics g, float cx, float cy, float rIn, float rOut,
                             int count, float width, float rotation, IntUnaryOperator colorOf) {
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = begin(g, VertexFormat.Mode.QUADS);
        float hw = width / 2f;
        for (int i = 0; i < count; i++) {
            int c = colorOf.applyAsInt(i);
            if (((c >>> 24) & 0xFF) == 0) continue;
            float a = rotation + TAU * i / count;
            float cos = Mth.cos(a), sin = Mth.sin(a);
            float px = -sin * hw, py = cos * hw; // перпендикуляр
            float x0 = cx + cos * rIn, y0 = cy + sin * rIn;
            float x1 = cx + cos * rOut, y1 = cy + sin * rOut;
            v(b, m, x0 - px, y0 - py, c);
            v(b, m, x0 + px, y0 + py, c);
            v(b, m, x1 + px, y1 + py, c);
            v(b, m, x1 - px, y1 - py, c);
        }
        end();
    }

    /** Звужена лінія (мазок): ширина {@code w0}→{@code w1}, колір {@code c0}→{@code c1}. */
    public static void line(GuiGraphics g, float x0, float y0, float x1, float y1,
                            float w0, float w1, int c0, int c1) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = Mth.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) return;
        float nx = -dy / len, ny = dx / len;
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = begin(g, VertexFormat.Mode.QUADS);
        v(b, m, x0 - nx * w0 / 2f, y0 - ny * w0 / 2f, c0);
        v(b, m, x0 + nx * w0 / 2f, y0 + ny * w0 / 2f, c0);
        v(b, m, x1 + nx * w1 / 2f, y1 + ny * w1 / 2f, c1);
        v(b, m, x1 - nx * w1 / 2f, y1 - ny * w1 / 2f, c1);
        end();
    }
}
