package com.log_to_kot.maniacmod.client.evac;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.net.s2c.evac.ClearingSyncPacket;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Позначка галявини для виживих: СУЦІЛЬНІ плоскі жовті квадрати на верхній грані
 * кожного блока зони — «килим», що лежить на землі й світиться (без освітлення, без рамок), як
 * заливка квадрата пастки в {@code TrapPlacementRenderer}, лише на всю грань блока.
 *
 * Показується ЗАВЖДИ, поки сервер надіслав галявини (фаза втечі) — тримати ракету не обов'язково:
 * гравець може не мати предмета в руках, а місце, куди її пускати, він має бачити.
 *
 * <h3>Як знаходимо землю (БАГФІКС: квадрати не з'являлись)</h3>
 * Раніше висоту брали з {@code MOTION_BLOCKING_NO_LEAVES}, але цей heightmap сервер клієнту НЕ
 * шле (на клієнті він рахується ліниво й не завжди є), тож квадрати лягали не туди або взагалі
 * зникали. Тепер береться {@code MOTION_BLOCKING} (його клієнт має завжди) і далі ми самі
 * спускаємось по колонці вниз, пропускаючи листя та блоки без колізії (трава, квіти, килими), доки
 * не знайдемо справжню поверхню. Висоту грані беремо з форми колізії, тож квадрат лягає на
 * плиту/сніг, а не висить над нею.
 *
 * <h3>Продуктивність</h3>
 * Колонки в радіусі {@link #DRAW_RADIUS} від гравця (≤ ~2,4 тис. квадратів). Висоти поверхні
 * кешуються на {@link #CACHE_MS} мс — раз на кадр шукати землю в тисячі колонок не треба.
 * Рішення «чи в зоні» приймає сервер, це лише підказка.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClearingZoneRenderer {

    private static final int DRAW_RADIUS = 24;
    /** Скільки блоків максимум спускаємось колонкою в пошуках землі. */
    private static final int MAX_DESCENT = 40;
    /** Мінімальний підйом над гранню блока; росте з відстанню, щоб не мерехтіло (буфер глибини грубшає). */
    private static final double LIFT_BASE = 0.02;
    private static final double LIFT_PER_BLOCK = 0.0006;
    /** Колір (насичений жовто-бурштиновий, як на еталонному фото) і прозорість. */
    private static final float R = 1.0f, G = 0.74f, B = 0.04f;
    private static final float ALPHA_MIN = 0.50f, ALPHA_MAX = 0.62f;
    private static final long CACHE_MS = 400L;

    private static final Map<Long, Double> SURFACE_CACHE = new HashMap<>();
    private static long cacheStamp = 0L;

    private ClearingZoneRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!ClientMatchState.isSurvivor() || ClientEvac.clearings().isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Level level = mc.level;
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f m = pose.last().pose();

        long now = System.currentTimeMillis();
        if (now - cacheStamp > CACHE_MS) {
            SURFACE_CACHE.clear();
            cacheStamp = now;
        }
        // Ледь помітне «дихання», щоб зона читалась як підказка, а не як частина карти.
        float alpha = ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * (0.5f + 0.5f * (float) Math.sin(now / 450.0));

        int px = mc.player.blockPosition().getX(), pz = mc.player.blockPosition().getZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (ClearingSyncPacket.Rect r : ClientEvac.clearings()) {
            int x0 = Math.max(r.minX(), px - DRAW_RADIUS), x1 = Math.min(r.maxX(), px + DRAW_RADIUS);
            int z0 = Math.max(r.minZ(), pz - DRAW_RADIUS), z1 = Math.min(r.maxZ(), pz + DRAW_RADIUS);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    final int fx = x, fz = z;
                    double top = SURFACE_CACHE.computeIfAbsent(BlockPos.asLong(x, 0, z),
                        k -> surfaceTop(level, cursor, fx, fz));
                    if (Double.isNaN(top)) continue;

                    double dx = x + 0.5 - cam.x, dz = z + 0.5 - cam.z;
                    double dist = Math.sqrt(dx * dx + dz * dz + (top - cam.y) * (top - cam.y));
                    float y = (float) (top + LIFT_BASE + dist * LIFT_PER_BLOCK - cam.y);
                    float ax = (float) (x - cam.x), bx = ax + 1f;
                    float az = (float) (z - cam.z), bz = az + 1f;
                    // debugQuads без culling — одна грань видна і зверху, і знизу.
                    quads.vertex(m, ax, y, az).color(R, G, B, alpha).endVertex();
                    quads.vertex(m, ax, y, bz).color(R, G, B, alpha).endVertex();
                    quads.vertex(m, bx, y, bz).color(R, G, B, alpha).endVertex();
                    quads.vertex(m, bx, y, az).color(R, G, B, alpha).endVertex();
                }
            }
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    /**
     * Y верхньої грані справжньої землі в колонці; {@code NaN}, якщо колонка не завантажена.
     * Спускаємось від {@code MOTION_BLOCKING}, пропускаючи листя й блоки без колізії та без рідини.
     */
    private static double surfaceTop(Level level, BlockPos.MutableBlockPos cursor, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return Double.NaN;
        int minY = level.getMinBuildHeight();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        for (int i = 0; i < MAX_DESCENT && y >= minY; i++, y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir() || state.is(BlockTags.LEAVES)) continue;
            if (!state.getFluidState().isEmpty()) {
                // Вода/лава: кладемо на її поверхню.
                return y + state.getFluidState().getHeight(level, cursor);
            }
            VoxelShape shape = state.getCollisionShape(level, cursor);
            if (shape.isEmpty()) continue;                       // трава, квіти, килим без колізії
            return y + shape.max(Direction.Axis.Y);
        }
        return Double.NaN;
    }
}
