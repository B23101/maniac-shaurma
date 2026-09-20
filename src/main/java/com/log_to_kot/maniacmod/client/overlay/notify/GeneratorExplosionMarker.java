package com.log_to_kot.maniacmod.client.overlay.notify;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Червоний маркер вибухнулого генератора, видимий на ЛЮБІЙ відстані.
 *
 * ── Чому це екранна проєкція, а не світова геометрія ────────────────────
 * Генератор має бути видно з 1000+ блоків, але:
 *  • сервер відстежує сутності лише в радіусі огляду сервера, далі
 *    сутності в клієнта просто немає;
 *  • чанки за радіусом огляду клієнта не завантажені;
 *  • світова геометрія (промінь, куб) обрізається дальньою площиною
 *    камери, яка залежить від налаштувань гравця.
 * Екранна проєкція позиції від цього не залежить: беремо матриці
 * камери з {@link RenderLevelStageEvent}, множимо на позицію
 * та малюємо мітку в GUI. Якщо генератор за спиною чи за краєм екрана,
 * мітка притискається до краю й вказує напрямок.
 *
 * ── Час ────────────────────────────────────────────────────────────────
 * Як і {@code GeneratorCompletedOverlay}, порахований від
 * {@code System.currentTimeMillis()}, а не від тіків: мітка має
 * згаснути навіть коли відкрите меню й клієнтські тіки не рахуються.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GeneratorExplosionMarker {

    private static final int RED = 0xFF3030;
    private static final int EDGE_MARGIN = 24;
    private static final int RADIUS = 7;

    /** Позиція → момент (мс), коли мітка гасне. Порядок вставки зберігається. */
    private static final Map<BlockPos, Long> activeUntilMs = new LinkedHashMap<>();

    /** Проєкція × вид із останнього кадру світу. */
    private static final Matrix4f viewProjection = new Matrix4f();
    private static Vec3 cameraPos = Vec3.ZERO;
    private static boolean matricesValid = false;

    private GeneratorExplosionMarker() {}

    public static void show(BlockPos pos, int durationTicks) {
        activeUntilMs.put(pos.immutable(), System.currentTimeMillis() + durationTicks * 50L);
    }

    /** Викликається зі {@code ClientMatchState.reset()} — кінець матчу гасить усе. */
    public static void reset() {
        activeUntilMs.clear();
        matricesValid = false;
    }

    /**
     * Запам'ятовує матриці камери. AFTER_PARTICLES — стадія, коли
     * {@code PoseStack} уже містить поворот камери (проєкція приходить
     * окремо), а на екран ще нічого не намальовано поверх світу.
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (activeUntilMs.isEmpty()) return;
        Camera camera = event.getCamera();
        viewProjection.set(event.getProjectionMatrix()).mul(event.getPoseStack().last().pose());
        cameraPos = camera.getPosition();
        matricesValid = true;
    }

    public static void render(GuiGraphics graphics) {
        if (activeUntilMs.isEmpty() || !matricesValid) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        Iterator<Map.Entry<BlockPos, Long>> it = activeUntilMs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> entry = it.next();
            long leftMs = entry.getValue() - now;
            if (leftMs <= 0) {
                it.remove();
                continue;
            }
            drawOne(graphics, mc, entry.getKey(), leftMs, width, height, now);
        }
    }

    private static void drawOne(GuiGraphics graphics, Minecraft mc, BlockPos pos,
                                long leftMs, int width, int height, long now) {
        double wx = pos.getX() + 0.5;
        double wy = pos.getY() + 1.5;
        double wz = pos.getZ() + 0.5;

        Vector4f clip = new Vector4f(
            (float) (wx - cameraPos.x), (float) (wy - cameraPos.y), (float) (wz - cameraPos.z), 1.0f);
        viewProjection.transform(clip);
        if (Math.abs(clip.w) < 1.0e-6f) return;

        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        if (clip.w < 0) {
            // За спиною камери: проєкція дзеркальна, розвертаємо, щоб мітка
            // показувала бік, куди треба повернутись.
            ndcX = -ndcX;
            ndcY = -ndcY;
            if (Math.abs(ndcX) < 0.05f && Math.abs(ndcY) < 0.05f) ndcY = -1.0f;
        }

        int sx = Math.round((ndcX * 0.5f + 0.5f) * width);
        int sy = Math.round((1.0f - (ndcY * 0.5f + 0.5f)) * height);
        sx = Math.max(EDGE_MARGIN, Math.min(width - EDGE_MARGIN, sx));
        sy = Math.max(EDGE_MARGIN, Math.min(height - EDGE_MARGIN, sy));

        // Пульсація й швидке згасання в останні 400 мс.
        double pulse = 0.65 + 0.35 * Math.sin(now / 90.0);
        double fade = Math.min(1.0, leftMs / 400.0);
        int alpha = (int) Math.max(30, Math.min(255, 255 * pulse * fade));
        int color = (alpha << 24) | RED;

        for (int dy = -RADIUS; dy <= RADIUS; dy++) {
            int half = RADIUS - Math.abs(dy);
            graphics.fill(sx - half, sy + dy, sx + half + 1, sy + dy + 1, color);
        }

        double dx = wx - cameraPos.x;
        double dz = wz - cameraPos.z;
        int meters = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        String label = Component.translatable("maniacmod.hud.generator.exploded", meters).getString();
        int textColor = (Math.max(90, alpha) << 24) | 0xFFFFFF;
        graphics.drawCenteredString(mc.font, label, sx, sy + RADIUS + 4, textColor);
    }
}
