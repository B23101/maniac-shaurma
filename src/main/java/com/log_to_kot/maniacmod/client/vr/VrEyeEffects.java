package com.log_to_kot.maniacmod.client.vr;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ManiacHitEffect;
import com.log_to_kot.maniacmod.client.SpikeHitEffect;
import com.log_to_kot.maniacmod.client.overlay.effects.SmokeScreenOverlay;
import com.log_to_kot.maniacmod.client.overlay.vitals.LowHpVignetteOverlay;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * VR: повноекранні ефекти (удар маньяка, шипи, дим, віньєтка хп) — НА ОЧІ,
 * а не на площині HUD.
 *
 * ── Проблема ─────────────────────────────────────────────────────────
 * Vivecraft малює весь 2D-інтерфейс в один буфер і показує його однією
 * площиною (у матчі — перед обличчям, див. {@link VrHudLock}). Спалах і
 * віньєтка, намальовані туди, стають прямокутником у просторі, який не
 * закриває периферію й не «б'є по очах».
 *
 * ── Що робимо ────────────────────────────────────────────────────────
 * Vivecraft рендерить світ окремо для кожного ока (ванільним
 * {@code LevelRenderer.renderLevel}), тож подія Forge {@code RenderLevelStageEvent}
 * приходить по одному разу на око. На етапі AFTER_LEVEL ми ставимо ту саму
 * ортографічну проєкцію, що й ванільний GUI-прохід (координати 0..guiW × 0..guiH
 * на ВЕСЬ буфер ока), і викликаємо ті самі {@code render(GuiGraphics)} цих
 * ефектів. Ефекти малюють {@code graphics.fill} по розмірах вікна, тож у цій
 * проєкції вони самі розтягуються на весь кадр кожного ока.
 *
 * У HUD-проході ці ефекти у VR вимкнені (див. {@code ClientSetup}) — щоб не
 * було подвійного малювання.
 *
 * ── Свідомі межі ─────────────────────────────────────────────────────
 *  • Лише чотири «справжні» повноекранні ефекти. Інфо-оверлеї (оглушення,
 *    розкриття ролі, інтро, титри) лишаються на площині HUD.
 *  • Ефекти кличуться двічі за кадр (по оку). Вони залежать від часу, а не
 *    від кількості викликів; дим спавнить плями за таймером — подвоєння
 *    не дає.
 *  • Подія приходить і для додаткових камер Vivecraft (дзеркало/третя особа):
 *    їх відфільтровано за {@code currentPass} (LEFT/RIGHT/CENTER). Це ВНУТРІШНЄ
 *    поле Vivecraft; якщо його не знайдено — фільтра немає, ефект просто
 *    з'явиться й там (на гру не впливає).
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VrEyeEffects {

    /** Та сама дальня площина, що й у ванільного GUI-проходу (Forge 1.20.1). */
    private static final float GUI_FAR_PLANE = 11000.0F;

    private VrEyeEffects() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (!VrCompat.eyeEffects()) return;
        // Лише вигляд очей: у дзеркалі/третій особі/камері Vivecraft ефект зайвий.
        if (!com.log_to_kot.maniacmod.vr.VrApiReflectAccess.isFirstPersonPass()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        Matrix4f oldProjection = RenderSystem.getProjectionMatrix();
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        PoseStack modelView = RenderSystem.getModelViewStack();

        RenderSystem.setProjectionMatrix(
            new Matrix4f().setOrtho(0.0F, w, h, 0.0F, 1000.0F, GUI_FAR_PLANE),
            VertexSorting.ORTHOGRAPHIC_Z);
        modelView.pushPose();
        modelView.setIdentity();
        modelView.translate(0.0F, 0.0F, 10000.0F - GUI_FAR_PLANE);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        try {
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            // Порядок як у ClientSetup: удар → шипи → дим → віньєтка хп.
            ManiacHitEffect.render(graphics);
            com.log_to_kot.maniacmod.client.ManiacHitConfirmFx.render(graphics);
            SpikeHitEffect.render(graphics);
            SmokeScreenOverlay.render(graphics);
            com.log_to_kot.maniacmod.client.overlay.vitals.ManiacProximityDarkOverlay.render(graphics);
            LowHpVignetteOverlay.render(graphics);
            graphics.flush();
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            RenderSystem.enableDepthTest();
        }
    }
}
