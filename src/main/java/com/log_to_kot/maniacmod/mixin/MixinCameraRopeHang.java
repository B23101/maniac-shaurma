package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.rope.RopeHangClient;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Камера підвішеного йде за його ГОЛОВОЮ (внизу перевернутого тіла), а не за
 * ванільною висотою очей 1.62. Крен на 180° додає Forge-подія в
 * {@code RopeHangClient.onCameraAngles}; тут лише положення. Той самий
 * підхід, що {@code MixinCameraWakeUp}. require = 0: якщо мапінг розійдеться,
 * камера просто лишиться на ванільній висоті.
 */
@Mixin(Camera.class)
public abstract class MixinCameraRopeHang {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"), require = 0)
    private void maniacmod$followHangingHead(BlockGetter level, Entity entity, boolean detached,
                                             boolean mirrored, float partialTick, CallbackInfo ci) {
        if (detached) return;
        Vec3 shift = RopeHangClient.cameraShift(entity);
        if (shift == null) return;
        Vec3 p = ((Camera) (Object) this).getPosition();
        this.setPosition(p.x + shift.x, p.y + shift.y, p.z + shift.z);
    }
}
