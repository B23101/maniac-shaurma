package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Лежача поза непритомного/повзучого виживого — напряму на
 * {@code PlayerModel}, у {@code setupAnim TAIL}.
 *
 * ── Чому лежачого не було видно ──────────────────────────────────────
 * {@code MixinPlayerDownedPose} ставить {@code Pose.SWIMMING}, і
 * ванільний {@code PlayerModel.setupAnim} для цієї пози сам крутить
 * тіло як плавця (руки й ноги гребуть від {@code limbSwing}). Кліп
 * PAL {@code downed_lie.json} на шарі DEATH_LIE не гарантовано
 * перемагає цей код — саме той клас бага, що з ліхтариком і
 * генератором. Тому — той самий надійний шлях: запис у TAIL, останнім.
 *
 * ── Вигляд ───────────────────────────────────────────────────────────
 * Ванільна SWIMMING-поза вже кладе тіло горизонтально (це робить
 * рендерер через {@code setupRotations}, а не модель), тож тут лише
 * прибираємо «гребки» й ставимо позу «лежить на животі, спираючись на
 * лікті»: голова піднята, руки зігнуті вперед, ноги випрямлені й
 * розслаблені. Повзучий (рухається) додатково повільно перебирає
 * руками — відразу видно, що він ЖИВИЙ і повзе, а не застиг.
 *
 * ── Джерело стану ────────────────────────────────────────────────────
 * {@link ClientMatchState#isProne} — той самий, що й у
 * {@code DownedPose}.
 *
 * ── require = 0 ──────────────────────────────────────────────────────
 * Якщо мапінг розійдеться — інжект мовчки не застосується.
 */
@Mixin(PlayerModel.class)
public abstract class MixinDownedPoseThirdPerson {

    @Inject(method = "setupAnim", at = @At("TAIL"), require = 0)
    private void maniacmod$applyDownedPose(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                            float ageInTicks, float netHeadYaw, float headPitch,
                                            CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;
        if (!ClientMatchState.isProne(player.getUUID())) return;

        PlayerModel<?> m = (PlayerModel<?>) (Object) this;

        // Рух повзком: 0 — лежить на місці, ~1 — активно повзе.
        float crawl = Mth.clamp(limbSwingAmount * 2.0f, 0.0f, 1.0f);
        float stroke = Mth.cos(limbSwing * 0.6662f) * crawl;

        // Голова: піднята вперед-вгору, повернута за камерою лише трохи,
        // щоб не «ламати шию» при взгляді в землю.
        m.head.xRot = -0.55f + Mth.clamp(headPitch * ((float) Math.PI / 180f), -0.25f, 0.25f) * 0.5f;
        m.head.yRot = netHeadYaw * ((float) Math.PI / 180f) * 0.5f;
        m.head.zRot = 0.0f;

        // Тулуб: рівний; рендерер уже поклав його горизонтально.
        m.body.xRot = 0.0f;
        m.body.yRot = 0.0f;
        m.body.zRot = 0.0f;

        // Руки: зігнуті вперед, лікті вперті в землю; при русі — гребок по черзі.
        m.rightArm.xRot = -2.35f + stroke * 0.55f;
        m.rightArm.yRot = -0.35f;
        m.rightArm.zRot = 0.30f;

        m.leftArm.xRot = -2.35f - stroke * 0.55f;
        m.leftArm.yRot = 0.35f;
        m.leftArm.zRot = -0.30f;

        // Ноги: випрямлені, злегка розведені, при русі — дрібне відштовхування.
        m.rightLeg.xRot = 0.10f - stroke * 0.18f;
        m.rightLeg.yRot = 0.0f;
        m.rightLeg.zRot = 0.14f;

        m.leftLeg.xRot = 0.10f + stroke * 0.18f;
        m.leftLeg.yRot = 0.0f;
        m.leftLeg.zRot = -0.14f;

        // Одяг має повторювати основні частини, інакше «рукави» лишаються у плаванні.
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
        m.jacket.copyFrom(m.body);
        m.hat.copyFrom(m.head);
    }
}
