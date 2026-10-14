package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.ManiacAvatar;
import com.log_to_kot.maniacmod.offline.ManiacOffline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.shaurmalib.forge.client.offline.OfflineAvatarRendererBase;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Рендер тіла офлайн-гравця: усе (шкіра, броня, предмет у руці) дає базовий рендерер lib,
 * тут додаються лише пози режиму, які ваніль для не-гравця не малює.
 *
 * <ul>
 *   <li>{@link ManiacOffline#POSE_CRAWL}: непритомний повзе: тіло горизонтально обличчям
 *       до землі (той самий поворот, що ваніль дає плаванню гравця).</li>
 *   <li>{@link ManiacOffline#POSE_LIE}: лежить після падіння: те саме, але на спині.</li>
 * </ul>
 * Чисто косметика: хітбокс і стан ведуть сервер ({@code SurvivorModule#syncBodyPoses}).
 */
@OnlyIn(Dist.CLIENT)
public class ManiacAvatarRenderer extends OfflineAvatarRendererBase<ManiacAvatar> {

    public ManiacAvatarRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void setupRotations(ManiacAvatar entity, PoseStack poseStack, float bob,
                                  float yBodyRot, float partialTick) {
        super.setupRotations(entity, poseStack, bob, yBodyRot, partialTick);

        int pose = entity.getCustomPoseId();
        if (pose != ManiacOffline.POSE_CRAWL && pose != ManiacOffline.POSE_LIE) return;

        // Як PlayerRenderer для плавання: лягти горизонтально й опустити до землі.
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poseStack.translate(0.0F, -1.0F, 0.3F);
        if (pose == ManiacOffline.POSE_LIE) {
            // Перевернути навколо поздовжньої осі тіла: обличчям угору.
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        }
    }
}
