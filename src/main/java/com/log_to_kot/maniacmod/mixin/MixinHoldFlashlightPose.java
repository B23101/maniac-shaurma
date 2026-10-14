package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.items.FlashlightItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Перша особа: піднімає ліхтарик до рівня плечей, доки він у руці.
 *
 * ── Чому саме {@code applyItemArmTransform} ──────────────────────────
 * У ванільному першому обличчі рука гравця сама НЕ малюється, якщо в
 * руці є предмет — {@code ItemInHandRenderer.renderArmWithItem} викликає
 * {@code renderPlayerArm} ЛИШЕ у гілці порожньої руки. Тобто поза тіла
 * (PlayerAnimator, {@code flashlight_idle.json}) у першій особі не
 * видно взагалі — вона впливає лише на третю особу/інших гравців.
 * Щоб підняти предмет у першій особі, треба зсунути власне його
 * рендер. {@code applyItemArmTransform(poseStack, arm, equipProgress)}
 * — це останнє перетворення перед {@code renderItem(...)} у
 * {@code renderArmWithItem}, тому додаткові translate/rotate у його
 * кінці зсувають сам предмет (і вдають підняту руку).
 *
 * ── require = 0 ──────────────────────────────────────────────────────
 * Якщо назва методу розійдеться з мапінгом у майбутній версії, цей
 * інжект мовчки не застосується (ліхтарик лишиться у звичайній
 * позиції), а не покладе гру помилкою Mixin. Перевіряти вигляд у грі.
 */
@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
public class MixinHoldFlashlightPose {

    @Inject(method = "applyItemArmTransform", at = @At("TAIL"), require = 0)
    private void maniacmod$raiseHeldFlashlight(PoseStack poseStack, HumanoidArm arm, float equipProgress,
                                               CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        ItemStack held = player.getMainArm() == arm
                ? player.getMainHandItem()
                : player.getOffhandItem();
        if (!FlashlightItem.isFlashlight(held)) return;

        // Підняти й трохи нахилити вгору — «рука на рівні плечей».
        poseStack.translate(0f, 0.10f, -0.05f);
        poseStack.mulPose(Axis.XP.rotationDegrees(-8f));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-3f));
    }
}
