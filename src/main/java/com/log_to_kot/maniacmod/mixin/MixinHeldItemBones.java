package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.anim.HeldItemBones;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Застосовує кістки {@code right_item}/{@code left_item} Blockbench-поз до предмета в руці
 * (третя особа) — див. {@link HeldItemBones}, там причини, чому раніше вони не діяли.
 *
 * Вставка — ПЕРЕД викликом {@code ItemInHandRenderer.renderItem} усередині ванільного
 * {@code renderArmWithItem}: стек уже у системі предмета (рука → поворот -90°/180° → зсув кулака),
 * тож наш зсув/поворот/масштаб додається до звичного положення предмета, а не замінює його.
 * Це {@code @Inject}, не {@code @Redirect}: інші моди (PlayerAnimator тощо), що чіпають той самий
 * виклик, не конфліктують. {@code require = 0} — розбіжність мапінгу не валить гру.
 */
@Mixin(ItemInHandLayer.class)
public abstract class MixinHeldItemBones {

    @Inject(method = "renderArmWithItem",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"),
            require = 0)
    private void maniacmod$applyItemBone(LivingEntity entity, ItemStack stack, ItemDisplayContext context,
                                         HumanoidArm arm, PoseStack pose, MultiBufferSource buffer,
                                         int light, CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;
        HeldItemBones.Transform t = HeldItemBones.forHand(player, arm, stack, Minecraft.getInstance().getFrameTime());
        if (t == null) return;

        pose.translate(t.px / 16f, t.py / 16f, t.pz / 16f);
        if (t.rx != 0f || t.ry != 0f || t.rz != 0f) {
            pose.mulPose(new Quaternionf().rotationZYX(
                (float) Math.toRadians(t.rz), (float) Math.toRadians(t.ry), (float) Math.toRadians(t.rx)));
        }
        if (t.sx != 1f || t.sy != 1f || t.sz != 1f) pose.scale(t.sx, t.sy, t.sz);
    }
}
