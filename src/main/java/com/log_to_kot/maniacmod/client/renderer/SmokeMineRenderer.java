package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.SmokeMineEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

/**
 * Рендерер димової міни: Geo-модель + кільце-пульс по підлозі.
 *
 * ── Що малюється ─────────────────────────────────────────────────────
 *   1. Geo-модель ({@link SmokeMineGeoModel}): жовтий кант із
 *      «знаком небезпеки», темна пластина, кришка й червоний
 *      світлодіод, що блимає раз на секунду (анімація {@code idle}).
 *   2. Світлодіод горить і в темряві: {@link AutoGlowingGeoLayer}
 *      малює піксели з {@code smoke_mine_glowmask.png} на повну яскравість.
 *   3. Кільце-пульс на підлозі — як і раніше, чиста геометрія
 *      ({@code RenderType.debugQuads()}): розбігається кожні півтори
 *      секунди й гасне, тож міну видно здалеку, коли саму модель ще
 *      не розгледіти. Геометрія без світла — у темних зонах не губиться.
 *
 * ── Світло ───────────────────────────────────────────────────────────
 * Блокове світло моделі піднято до мінімуму {@link #MIN_BLOCK_LIGHT}:
 * міна не повинна «вмикатись» у повній темряві чорним силуетом — вона
 * має бути помітною пасткою (і маньяку, і виживим).
 *
 * ── Після спрацювання ────────────────────────────────────────────────
 * Нічого не малюється — міна «зникла», лишається хмара диму.
 */
@OnlyIn(Dist.CLIENT)
public class SmokeMineRenderer extends GeoEntityRenderer<SmokeMineEntity> {

    /** Мінімальне блокове світло моделі (0..15): у темряві міна не чорна. */
    private static final int MIN_BLOCK_LIGHT = 9;

    /** Скільки сегментів у колі (парне число: диск малюється чвірками). */
    private static final int SEGMENTS = 16;
    private static final float[] COS = new float[SEGMENTS + 1];
    private static final float[] SIN = new float[SEGMENTS + 1];
    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = Math.PI * 2.0 * i / SEGMENTS;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
    }

    /** Висота кільця-пульсу над підлогою, у блоках (мінімальний зазор проти z-fighting). */
    private static final float Y_PULSE = 0.018f;

    /** Радіус, від якого стартує пульс (зовнішній край моделі), у блоках. */
    private static final float RIM_OUTER = 0.36f;

    /** Період пульсу-кільця, у тіках (20 тіків = 1 с). */
    private static final float PULSE_PERIOD_TICKS = 30f;

    /**
     * Перемикач кільця-пульсу. {@code false} — кільце не малюється (щоб міна
     * не «світилась» на всю карту). Вся геометрія нижче лишається робочою:
     * щоб повернути пульс або використати його для іншої пастки, досить
     * поставити {@code true}.
     */
    private static final boolean PULSE_RING_ENABLED = false;

    public SmokeMineRenderer(EntityRendererProvider.Context context) {
        super(context, new SmokeMineGeoModel());
        // Тінь не потрібна: міна пласка.
        this.shadowRadius = 0.0f;
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
    }

    @Override
    public void render(SmokeMineEntity mine, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        if (mine.isTriggered()) return;

        int light = LightTexture.pack(
            Math.max(LightTexture.block(packedLight), MIN_BLOCK_LIGHT),
            LightTexture.sky(packedLight));
        super.render(mine, entityYaw, partialTick, pose, buffers, light);

        if (!PULSE_RING_ENABLED) return;

        // Кільце-пульс: розбігається від краю моделі й гасне.
        float time = mine.tickCount + partialTick;
        float pt = (time % PULSE_PERIOD_TICKS) / PULSE_PERIOD_TICKS;
        float pulseAlpha = (float) Math.pow(1.0f - pt, 1.4);
        float pulseOuter = RIM_OUTER + 0.85f * pt;
        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f m = pose.last().pose();
        ring(vc, m, Y_PULSE, Math.max(RIM_OUTER, pulseOuter - 0.06f), pulseOuter,
            255, 40, 32, Math.round(215 * pulseAlpha));
    }

    // ── Геометрія ────────────────────────────────────────────────────────

    /** Кільце між радіусами {@code rIn} і {@code rOut}. */
    private static void ring(VertexConsumer vc, Matrix4f m, float y, float rIn, float rOut,
                             int cr, int cg, int cb, int ca) {
        if (ca <= 0 || rOut <= rIn) return;
        for (int i = 0; i < SEGMENTS; i++) {
            vertex(vc, m, COS[i] * rIn, y, SIN[i] * rIn, cr, cg, cb, ca);
            vertex(vc, m, COS[i] * rOut, y, SIN[i] * rOut, cr, cg, cb, ca);
            vertex(vc, m, COS[i + 1] * rOut, y, SIN[i + 1] * rOut, cr, cg, cb, ca);
            vertex(vc, m, COS[i + 1] * rIn, y, SIN[i + 1] * rIn, cr, cg, cb, ca);
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, float x, float y, float z,
                               int r, int g, int b, int a) {
        vc.vertex(m, x, y, z).color(Mth.clamp(r, 0, 255), Mth.clamp(g, 0, 255),
            Mth.clamp(b, 0, 255), Mth.clamp(a, 0, 255)).endVertex();
    }
}
