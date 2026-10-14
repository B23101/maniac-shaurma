package com.log_to_kot.maniacmod.client.barricade;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.barricade.BarricadeGeometry;
import com.log_to_kot.maniacmod.barricade.BoardSpec;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * Клієнтська частина режиму будування барикади: малює буфер дощок (ще не зареєстрованих) і
 * ПРЕВ'Ю наступної дошки — від першої точки до місця, куди зараз дивишся, з текстурою блока в
 * руці. Усе, що тут малюється, береться з {@code BarricadeBuildStatePacket} і правил геометрії
 * сервера ({@link BarricadeGeometry}) — клієнт нічого не вирішує сам.
 *
 * <p>Поки режим увімкнено, ПКМ глушиться ТУТ (клієнтська гілка події), щоб ваніль не «поставила»
 * блок передбачувано. Forge усе одно шле серверу ПКМ по блоку, тож сервер отримує клік.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BarricadeBuildClient {

    private static final ResourceLocation DEFAULT_BLOCK = new ResourceLocation("minecraft", "oak_planks");
    private static final float GHOST_ALPHA = 0.6f;

    private static boolean active;
    private static List<BoardSpec> buffer = List.of();
    private static Vec3 pending;
    private static ResourceLocation lastBlock = DEFAULT_BLOCK;

    private BarricadeBuildClient() {}

    public static void onState(boolean newActive, List<BoardSpec> boards, Vec3 newPending) {
        active = newActive;
        buffer = boards;
        pending = newPending;
    }

    public static boolean isActive() { return active; }
    public static int boardCount() { return buffer.size(); }
    public static boolean hasPending() { return pending != null; }

    public static void reset() {
        onState(false, List.of(), null);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    // ── Глушимо ванільний ПКМ ────────────────────────────────────────────

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (active && event.getLevel().isClientSide()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (active && event.getLevel().isClientSide()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    // ── Малювання ────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        if (mc.player.getMainHandItem().getItem() instanceof BlockItem item) {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(item.getBlock());
            if (id != null) lastBlock = id;
        }

        // Буфер — вже зроблені дошки, з їхніми текстурами.
        if (!buffer.isEmpty()) {
            RenderType solid = RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS);
            VertexConsumer vc = buffers.getBuffer(solid);
            for (BoardSpec board : buffer) {
                PlankRenderer.draw(pose, vc, board, cam, lightAt(mc, board), 1.0f);
            }
            buffers.endBatch(solid);
        }

        // Точка під прицілом і прев'ю дошки.
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            Vec3 point = hit.getLocation().add(Vec3.atLowerCornerOf(hit.getDirection().getNormal())
                .scale(BarricadeGeometry.SURFACE_OFFSET));
            if (pending != null) {
                BoardSpec ghost = new BoardSpec(pending, point,
                    BarricadeGeometry.faceNormal(pending, point, mc.player.getEyePosition()), lastBlock);
                RenderType translucent = RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
                PlankRenderer.draw(pose, buffers.getBuffer(translucent), ghost, cam, lightAt(mc, ghost), GHOST_ALPHA);
                buffers.endBatch(translucent);
            }
            drawMarker(pose, buffers, point.subtract(cam), pending == null ? 0.4f : 0.9f);
        }
        if (pending != null) drawMarker(pose, buffers, pending.subtract(cam), 1.0f);
    }

    private static int lightAt(Minecraft mc, BoardSpec board) {
        Vec3 mid = board.a().add(board.b()).scale(0.5).add(board.normal().scale(0.15));
        return LevelRenderer.getLightColor(mc.level, BlockPos.containing(mid));
    }

    private static void drawMarker(PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 p, float alpha) {
        double r = 0.05;
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, lines, new AABB(p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r),
            0.3f, 1.0f, 0.4f, alpha);
        buffers.endBatch(RenderType.lines());
    }
}
