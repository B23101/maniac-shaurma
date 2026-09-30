package com.log_to_kot.maniacmod.client.overlay.notify;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * 5 зірочок, що крутяться НАД ГОЛОВОЮ оглушеного маньяка — тепер це
 * справжня 3D-геометрія у світі, а не GUI-мітка.
 *
 * ── Що було не так ───────────────────────────────────────────────────
 * Раніше зірки малювались у GUI через проєкцію точки на екран
 * ({@code WorldToScreen}) — тобто ПОВЕРХ усього кадру, без перевірки
 * глибини: їх було видно крізь блоки, а кільце будувалось у площині
 * екрана, тож висота залежала від того, звідки дивиться глядач, а не від
 * самого маньяка.
 *
 * ── Як тепер ─────────────────────────────────────────────────────────
 * Кільце лежить у ГОРИЗОНТАЛЬНІЙ площині світу на висоті
 * {@code y_ніг + bbHeight + зазор}, тобто над маківкою САМОГО маньяка, і
 * рухається разом із ним (інтерполяція за partialTick). Кожна зірка —
 * плоский білборд (повернутий до камери), який малюється зі ЗВИЧАЙНИМ
 * тестом глибини: блок між камерою й маньяком закриває зірки, як і
 * будь-який інший об'єкт світу.
 *
 * Свого оглушеного маньяка від першої особи не малюємо (зірки були б
 * прямо в об'єктиві); у третій особі — малюємо.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ManiacStunWorldMarker {

    /** Зазор між маківкою й центром кільця, блоки. */
    private static final double HEAD_GAP = 0.28;
    /** Радіус кільця, блоки. */
    private static final float RING_RADIUS = 0.42f;
    /** Радіус променів однієї зірки, блоки. */
    private static final float STAR_RADIUS = 0.15f;
    private static final int STARS = 5;
    private static final long ROTATION_PERIOD_MS = 2200L;

    private static final float R = 1.0f, G = 0.878f, B = 0.4f, A = 1.0f;

    private ManiacStunWorldMarker() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Camera camera = event.getCamera();
        float partial = event.getPartialTick();
        float spin = (System.currentTimeMillis() % ROTATION_PERIOD_MS) / (float) ROTATION_PERIOD_MS
            * Mth.TWO_PI;

        boolean began = false;
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buf = tesselator.getBuilder();
        Matrix4f pose = event.getPoseStack().last().pose();
        Vec3 cam = camera.getPosition();

        for (RosterSyncPacket.RosterEntry entry : ClientMatchState.roster()) {
            UUID id = entry.playerId();
            if (!ClientMatchState.isManiacStunned(id)) continue;

            Player maniac = mc.level.getPlayerByUUID(id);
            if (maniac == null) continue;
            if (maniac == mc.player && !camera.isDetached()) continue;

            if (!began) {
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(false);
                RenderSystem.disableCull();
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                buf.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
                began = true;
            }

            // Над САМИМ маньяком: інтерполяція за partialTick, висота — його власна.
            double cx = Mth.lerp(partial, maniac.xo, maniac.getX()) - cam.x;
            double cy = Mth.lerp(partial, maniac.yo, maniac.getY()) + maniac.getBbHeight() + HEAD_GAP - cam.y;
            double cz = Mth.lerp(partial, maniac.zo, maniac.getZ()) - cam.z;

            for (int i = 0; i < STARS; i++) {
                float a = spin + i * Mth.TWO_PI / STARS;
                float sx = (float) cx + Mth.cos(a) * RING_RADIUS;
                float sz = (float) cz + Mth.sin(a) * RING_RADIUS;
                // Легке погойдування по висоті — зірки «літають», але кільце не тікає від голови.
                float sy = (float) cy + Mth.sin(a * 2f) * 0.03f;
                drawStar(buf, pose, camera, sx, sy, sz, spin * 2f + i);
            }
        }

        if (began) {
            tesselator.end();
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    /** Плоска 5-променева зірка в площині камери (білборд), веером трикутників від центру. */
    private static void drawStar(BufferBuilder buf, Matrix4f pose, Camera camera,
                                 float x, float y, float z, float rot) {
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();

        float[] px = new float[10];
        float[] py = new float[10];
        for (int i = 0; i < 10; i++) {
            float r = (i % 2 == 0) ? STAR_RADIUS : STAR_RADIUS * 0.38f;
            float ang = rot + i * Mth.PI / 5f - Mth.HALF_PI;
            px[i] = Mth.cos(ang) * r;
            py[i] = Mth.sin(ang) * r;
        }
        for (int i = 0; i < 10; i++) {
            int j = (i + 1) % 10;
            buf.vertex(pose, x, y, z).color(R, G, B, A).endVertex();
            buf.vertex(pose, x + left.x * px[i] + up.x * py[i],
                             y + left.y * px[i] + up.y * py[i],
                             z + left.z * px[i] + up.z * py[i]).color(R, G, B, A).endVertex();
            buf.vertex(pose, x + left.x * px[j] + up.x * py[j],
                             y + left.y * px[j] + up.y * py[j],
                             z + left.z * px[j] + up.z * py[j]).color(R, G, B, A).endVertex();
        }
    }
}
