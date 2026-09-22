package com.log_to_kot.maniacmod.client.traps;

import com.log_to_kot.maniacmod.ManiacMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Малює квадрат-підказку режиму розміщення на верхній грані блока під
 * прицілом: ЗЕЛЕНИЙ — місце придатне, ЧЕРВОНИЙ — ні.
 *
 * ── Що саме малюється ────────────────────────────────────────────────
 * Плаский квадрат на верхній грані блока (трохи над нею, щоб не
 * мерехтів у одній площині з блоком) + яскравий контур по краю. Заливка
 * прозора, тож блок під нею видно.
 *
 * ── Чому ванільні буфери ─────────────────────────────────────────────
 * {@link RenderType#lines()} і {@link LevelRenderer#renderLineBox} —
 * ті самі, що ванілька використовує для рамки виділення блока. Вони
 * стабільні між патчами 1.20.x і не потребують власних шейдерів чи
 * текстур. Заливка — теж ванільний {@link RenderType#debugQuads()}-подібний
 * шар, тому нічого не треба реєструвати.
 *
 * ── Що НЕ перевірено ─────────────────────────────────────────────────
 * Цей клас написаний без можливості запустити гру. Ризикові місця —
 * порядок вершин заливки (якщо квадрат невидимий — це culling, поміняти
 * порядок) та стадія рендеру. Див. {@link #STAGE}.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrapPlacementRenderer {

    /**
     * Після прозорих блоків: квадрат прозорий і має лягти поверх світу,
     * а не за ним. Якщо його не видно крізь воду/скло — пробувати
     * {@code AFTER_PARTICLES}.
     */
    private static final RenderLevelStageEvent.Stage STAGE = RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;

    /** Підняти над гранню блока, щоб уникнути z-fighting. */
    private static final double LIFT = 0.01;

    /** Відступ від краю блока: квадрат трохи менший за грань — акуратніше виглядає. */
    private static final double INSET = 0.04;

    private static final float[] GREEN = { 0.20f, 0.90f, 0.30f };
    private static final float[] RED   = { 0.95f, 0.20f, 0.20f };

    private static final float FILL_ALPHA = 0.35f;
    private static final float LINE_ALPHA = 0.95f;

    private TrapPlacementRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != STAGE) return;
        if (!TrapPlacementController.isActive()) return;

        BlockPos target = TrapPlacementController.target();
        if (target == null) return;

        float[] color = TrapPlacementController.targetValid() ? GREEN : RED;

        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        // Координати блока відносно камери: PoseStack уже зсунутий у
        // просторі камери, тож віднімаємо її позицію самі.
        double x0 = target.getX() + INSET - cam.x;
        double x1 = target.getX() + 1.0 - INSET - cam.x;
        double z0 = target.getZ() + INSET - cam.z;
        double z1 = target.getZ() + 1.0 - INSET - cam.z;
        double y  = target.getY() + 1.0 + LIFT - cam.y;

        // Контур.
        AABB outline = new AABB(x0, y, z0, x1, y + 0.0, z1);
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, lines, outline,
            color[0], color[1], color[2], LINE_ALPHA);
        buffers.endBatch(RenderType.lines());

        // Заливка.
        renderFill(pose, buffers, x0, y, z0, x1, z1, color);
    }

    /**
     * Прозора плаская заливка. Малюється у ДВІ сторони (звідти й
     * знизу): маньяк може дивитись на блок зверху, а не з боку, і
     * culling однієї сторони зробив би квадрат невидимим під певним
     * кутом.
     */
    private static void renderFill(PoseStack pose, MultiBufferSource.BufferSource buffers,
                                    double x0, double y, double z0, double x1, double z1, float[] c) {
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        var m = pose.last().pose();

        float ax = (float) x0, az = (float) z0, bx = (float) x1, bz = (float) z1, fy = (float) y;
        // Зверху.
        quads.vertex(m, ax, fy, az).color(c[0], c[1], c[2], FILL_ALPHA).endVertex();
        quads.vertex(m, ax, fy, bz).color(c[0], c[1], c[2], FILL_ALPHA).endVertex();
        quads.vertex(m, bx, fy, bz).color(c[0], c[1], c[2], FILL_ALPHA).endVertex();
        quads.vertex(m, bx, fy, az).color(c[0], c[1], c[2], FILL_ALPHA).endVertex();
        // Знизу (зворотний обхід).
        quads.vertex(m, bx, fy, az).color(c[0], c[1], c[2], FILL_ALPHA).endVertex();
        quads.vertex(m, bx, fy, bz).color(c[0], c[1], c[2], FILL_ALPHA).endVertex();
        quads.vertex(m, ax, fy, bz).color(c[0], c[1], c[2], FILL_ALPHA).endVertex();
        quads.vertex(m, ax, fy, az).color(c[0], c[1], c[2], FILL_ALPHA).endVertex();
        buffers.endBatch(RenderType.debugQuads());
    }
}
