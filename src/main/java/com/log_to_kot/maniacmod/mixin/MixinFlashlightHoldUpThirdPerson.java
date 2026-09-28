package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.items.FlashlightItem;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Третя особа: піднімає руку з ліхтариком напряму на {@code PlayerModel},
 * той самий метод, що NotEnoughAnimations {@code LookAtItemAnimation}
 * (гілка {@code HoldUpTarget.NONE} — "звичайне тримання предмета на
 * видноті", не наведення на камеру) — формула перенесена звідти
 * 1-в-1, БЕЗ PlayerAnimator/shaurma-lib playerlib-шару, тому що PAL
 * керує позою через власний {@code ModifierLayer} стек, який
 * застосовується РАНІШЕ за {@code setupAnim} у рендер-конвеєрі (чи, в
 * певних версіях PAL, накладається адитивно поверх уже виставлених тут
 * кутів) — інжект у {@code setupAnim TAIL} гарантовано останній, як і
 * в самому NEA, тому виграє в порядку застосування незалежно від
 * PAL-шару {@code FlashlightItem.HOLD_POSE_ACTION_NAME} (той шар можна
 * лишити чи прибрати — він більше не потрібен для самої пози руки,
 * лише якщо десь використовується його fade/lifecycle окремо).
 * <p>
 * ── Формула (з {@code LookAtItemAnimation.apply}, case NONE) ──────────
 * <pre>
 *   pitch = -holdUpItemOffset - lerp(-1*(xRotCamera-90)/180, 1, 1.5)
 *   yaw   = -0.2f
 *   roll  = 0.3f
 * </pre>
 * {@code holdUpItemOffset} у NEA — конфигурований, тут захардкожено 0
 * (дефолт NEA) — немає власного налаштування для цього мода.
 * {@code xRotCamera} у NEA — {@code EntityUtil.getXRot(entity)}, тобто
 * {@code entity.getXRot(partialTick)}; тут беремо
 * {@code entity.getXRot()} (без інтерполяції — {@code setupAnim} не
 * отримує partialTick, той самий компроміс, що в багатьох подібних
 * мод-міксах).
 * <p>
 * {@code require = 0}: якщо сигнатура {@code setupAnim} чи назва поля
 * {@code rightArm}/{@code leftArm} розійдеться з мапінгом у майбутній
 * версії, інжект мовчки не застосується (рука лишиться в ванільній
 * позиції), а не покладе гру помилкою Mixin.
 */
@Mixin(PlayerModel.class)
public abstract class MixinFlashlightHoldUpThirdPerson {

    @Inject(method = "setupAnim", at = @At("TAIL"), require = 0)
    private void maniacmod$holdUpFlashlight(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                             float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;
        PlayerModel<?> self = (PlayerModel<?>) (Object) this;

        applyIfFlashlight(self, player.getMainHandItem(), player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT,
                self.rightArm, entity);
        applyIfFlashlight(self, player.getOffhandItem(), player.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT,
                self.leftArm, entity);
    }

    private static void applyIfFlashlight(PlayerModel<?> model, ItemStack stack, boolean isRightArm,
                                           net.minecraft.client.model.geom.ModelPart arm, LivingEntity entity) {
        if (!FlashlightItem.isFlashlight(stack)) return;

        // holdUpItemOffset = 0 (дефолт NEA, немає власного конфігу тут).
        float xRotCamera = entity.getXRot();
        float lerpFactor = -1f * (xRotCamera - 90f) / 180f;
        float pitch = -0f - Mth.lerp(lerpFactor, 1f, 1.5f);
        float yaw = -0.2f;
        float roll = 0.3f;

        arm.xRot = pitch;
        arm.yRot = isRightArm ? yaw : -yaw;   // дзеркалимо yaw для лівої руки — той самий підхід, що NEA applyArmTransforms
        arm.zRot = isRightArm ? roll : -roll; // і roll теж
    }
}
