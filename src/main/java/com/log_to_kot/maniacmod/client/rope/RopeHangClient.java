package com.log_to_kot.maniacmod.client.rope;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.entity.RopeTrapEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Клієнтський бік мотузки-підвісу: хто висить, камера догори дригом,
 * нахил моделі, введення A/D.
 *
 * ── Звідки клієнт знає, що хтось висить ─────────────────────────────
 * Не з окремих пакетів: {@link RopeTrapEntity} синхронізує id жертви,
 * кут гойдання й вісь. Раз на тік збираємо «id жертви → мотузка» з
 * сутностей, які клієнт бачить. Так самі сутність, поза й нитка завжди
 * згодні між собою.
 *
 * ── Камера «світ догори дригом» ─────────────────────────────────────
 * Крен камери на 180° (+ поточний нахил маятника) через
 * {@code ViewportEvent.ComputeCameraAngles} і зсув очей до голови
 * ({@code MixinCameraRopeHang}). GravityChanger (Fabric) сюди не
 * підключений — це Forge, а потрібен лише візуальний переворот.
 * ⚠ Знак нахилу камери — {@link #ROLL_SIGN}: якщо нахил йде «не туди»,
 * зміни на -1.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RopeHangClient {

    /** Від центру тіла до ніг (місце прив'язки нитки). Має збігатись із PAL-позою rope_hang.json. */
    public static final double FEET_FROM_CENTER = 1.0;
    /** Очі підвішеного: ~0.25 над низом голови; ванільні очі стоять на 1.62 над початком хітбокса. */
    public static final double EYE_SHIFT_Y = 0.25 - 1.62;
    public static final float ROLL_SIGN = 1.0f;
    /** Знак кивка камери, коли гойдається вперед/назад. Якщо кивок «не туди» — постав -1. */
    public static final float PITCH_SIGN = 1.0f;

    private static final Map<Integer, RopeTrapEntity> HUNG = new HashMap<>();
    private static final Set<Integer> PUSHED = new HashSet<>();

    private RopeHangClient() {}

    // ── Запити ───────────────────────────────────────────────────────────

    public static RopeTrapEntity ropeOf(Entity e) {
        return e == null ? null : HUNG.get(e.getId());
    }

    public static boolean isHung(Entity e) {
        return ropeOf(e) != null;
    }

    public static RopeTrapEntity localRope() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : HUNG.get(mc.player.getId());
    }

    public static boolean isLocalHung() {
        return localRope() != null;
    }

    /** Зсув камери (лише для власного гравця першої особи), або null. */
    public static Vec3 cameraShift(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (entity == null || entity != mc.player || !isHung(entity)) return null;
        return new Vec3(0, EYE_SHIFT_Y, 0);
    }

    // ── Тік: збір стану + введення A/D ───────────────────────────────────

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        HUNG.clear();
        if (mc.level == null || mc.player == null) return;

        // Поза ігровою фазою (кінець матчу) підвіс не діє, навіть якщо сутність ще не зникла.
        if (!ClientMatchState.allows(com.log_to_kot.maniacmod.core.phase.PhaseRule.TRAPS)) return;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof RopeTrapEntity r && r.isSnapped() && r.victimId() >= 0) {
                HUNG.put(r.victimId(), r);
            }
        }

        // A/D тепер читає ЛИШЕ екран RopeSwingScreen (відкривається по F).
    }

    // ── Камера ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        RopeTrapEntity r = localRope();
        if (r == null) return;
        float theta = r.swing((float) event.getPartialTick());
        // axis = cos(d)·вправо − sin(d)·вперед, де d = кут осі відносно погляду.
        // Вбік гойдання камера крениться (roll), вперед/назад — киває (pitch).
        double d = Math.toRadians(r.axisYaw() - Minecraft.getInstance().player.getYRot());
        double deg = Math.toDegrees(theta);
        event.setRoll(event.getRoll() + 180f + ROLL_SIGN * (float) (deg * Math.cos(d)));
        event.setPitch(event.getPitch() + PITCH_SIGN * (float) (-deg * Math.sin(d)));
    }

    /** Руки від першої особи в підвісі не малюємо: гравець висить догори ногами. */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (isLocalHung()) event.setCanceled(true);
    }

    // ── Нахил моделі підвішеного (для всіх глядачів) ─────────────────────
    // Переворот на 180° — тут (PoseStack): у PlayerModel голова й ноги не
    // дочірні до тулуба, тож PAL-поворот body їх би не перевернув. PAL
    // (rope_hang.json) лише піднімає руки, щоб у світі вони звисали вниз.
    // LOWEST + без receiveCanceled: якщо подію вже скасовано, ми не пушимо
    // матрицю (інакше Post не прийде й стек «потече»).

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        RopeTrapEntity r = HUNG.get(event.getEntity().getId());
        if (r == null) return;

        Vec3 a = r.swingAxis();
        Vector3f axis = new Vector3f((float) a.x, 0f, (float) a.z).cross(0f, 1f, 0f);   // a × up
        if (axis.lengthSquared() < 1.0E-6f) return;
        axis.normalize();

        float theta = r.swing(event.getPartialTick());
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        PUSHED.add(event.getEntity().getId());
        ps.translate(0.0, 1.0, 0.0);                       // крутимо навколо центру тіла
        ps.mulPose(new Quaternionf().fromAxisAngleRad(axis, theta));
        ps.mulPose(Axis.ZP.rotationDegrees(180f));          // догори ногами (вся модель, не лише тулуб)
        ps.translate(0.0, -1.0, 0.0);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        if (PUSHED.remove(event.getEntity().getId())) {
            event.getPoseStack().popPose();
        }
    }

    /** Скидання між матчами. */
    public static void reset() {
        HUNG.clear();
        PUSHED.clear();
    }
}
