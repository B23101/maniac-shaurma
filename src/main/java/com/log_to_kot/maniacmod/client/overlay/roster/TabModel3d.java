package com.log_to_kot.maniacmod.client.overlay.roster;

import com.log_to_kot.maniacmod.ManiacMod;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.UUID;

/**
 * Малює РЕАЛЬНІ 3D-моделі гравців (власна картка «ТИ») у прямокутнику GUI.
 * Маньяк і генератор сюди НЕ потрапляють: їх малює {@link TabGeoView} із
 * ассетів, без сутностей зі світу.
 *
 * ── Як це працює ─────────────────────────────────────────────────────
 * Копія ванільного {@code InventoryScreen.renderEntityInInventory} з
 * двома відмінностями: (1) приймає будь-яку {@link Entity} (генератор —
 * не {@code LivingEntity}), (2) весь стан позі/кута відновлюється в
 * {@code finally} — виняток посеред рендера не лишає {@code PoseStack}
 * незбалансованим, а гравця повернутим «обличчям до Tab».
 *
 * ── Глибина ──────────────────────────────────────────────────────────
 * Сутність малюється на z=50 і залишає в буфері глибини «слід»: без
 * очищення все, що GUI малює ПІСЛЯ (наступні картки, чат), пропадало б
 * там, де стояла модель. Тому після кожної моделі — {@code RenderSystem.clear}
 * глибини (256 = GL_DEPTH_BUFFER_BIT, як у самій ванілі). Побічний
 * ефект — ПІСЛЯ моделі можна безпечно малювати оверлеї (скан-лінії,
 * туман) поверх неї звичайним {@code fill}.
 *
 * ── Відмовостійкість ─────────────────────────────────────────────────
 * Якщо рендер сутності хоч раз кидає виняток (чужий мод, зламана
 * модель), 3D вимикається до перезапуску гри — Tab лишається робочим,
 * картки переходять на плоский фолбек (голова/іконка).
 */
@OnlyIn(Dist.CLIENT)
final class TabModel3d {

    private TabModel3d() {}

    private static boolean broken = false;

    static boolean available() {
        return !broken;
    }

    /** Гравець зі світу клієнта; {@code null}, якщо він поза зоною завантаження. */
    static Player playerOf(UUID id) {
        if (id == null) return null;
        Level level = Minecraft.getInstance().level;
        return level == null ? null : level.getPlayerByUUID(id);
    }

    /**
     * @param cx       центр моделі по X (координати GUI з урахуванням pose)
     * @param baseY    Y «ніг» моделі
     * @param scale    пікселів на блок висоти (гравець ≈ 1.8 → scale 40 ≈ 72 px)
     * @param yawDeg   поворот навколо вертикалі; 180 — обличчям до глядача
     * @param pitchDeg нахил камери, невеликий (±10)
     * @return {@code true}, якщо модель намальовано
     */
    static boolean render(net.minecraft.client.gui.GuiGraphics g, Entity entity,
                          float cx, float baseY, float scale, float yawDeg, float pitchDeg) {
        if (broken || entity == null || entity.isRemoved()) return false;

        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = g.pose();
        LivingEntity living = entity instanceof LivingEntity l ? l : null;

        float bodyRot = 0, bodyRotO = 0, yRot = 0, yRotO = 0, xRot = 0, xRotO = 0, headRot = 0, headRotO = 0;
        if (living != null) {
            bodyRot = living.yBodyRot;   bodyRotO = living.yBodyRotO;
            yRot = living.getYRot();     yRotO = living.yRotO;
            xRot = living.getXRot();     xRotO = living.xRotO;
            headRot = living.yHeadRot;   headRotO = living.yHeadRotO;

            living.yBodyRot = yawDeg;    living.yBodyRotO = yawDeg;
            living.setYRot(yawDeg);      living.yRotO = yawDeg;
            living.setXRot(0f);          living.xRotO = 0f;
            living.yHeadRot = yawDeg;    living.yHeadRotO = yawDeg;
        }

        Quaternionf cameraOrientation = new Quaternionf().rotateX((float) Math.toRadians(pitchDeg));
        Quaternionf model = new Quaternionf().rotateZ((float) Math.PI);
        model.mul(cameraOrientation);

        boolean drawn = false;
        pose.pushPose();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        try {
            pose.translate(cx, baseY, 50.0);
            pose.mulPoseMatrix(new Matrix4f().scaling(scale, scale, -scale));
            pose.mulPose(model);
            if (living == null) {
                // Не-Living сутність (генератор) не має власного «погляду» —
                // крутимо саму сцену.
                pose.mulPose(Axis.YP.rotationDegrees(yawDeg));
            }

            Lighting.setupForEntityInInventory();
            cameraOrientation.conjugate();
            dispatcher.overrideCameraOrientation(cameraOrientation);
            dispatcher.setRenderShadow(false);
            RenderSystem.runAsFancy(() ->
                dispatcher.render(entity, 0.0, 0.0, 0.0, 0.0f, 1.0f, pose, g.bufferSource(), 15728880));
            g.flush();
            drawn = true;
        } catch (RuntimeException | LinkageError failure) {
            broken = true;
            ManiacMod.LOGGER.warn("[tab] 3D-рендер сутності {} вимкнено до перезапуску: {}",
                entity.getType(), failure.toString());
        } finally {
            dispatcher.setRenderShadow(true);
            pose.popPose();
            Lighting.setupFor3DItems();
            if (living != null) {
                living.yBodyRot = bodyRot;   living.yBodyRotO = bodyRotO;
                living.setYRot(yRot);        living.yRotO = yRotO;
                living.setXRot(xRot);        living.xRotO = xRotO;
                living.yHeadRot = headRot;   living.yHeadRotO = headRotO;
            }
        }
        RenderSystem.clear(256, Minecraft.ON_OSX);
        return drawn;
    }
}
