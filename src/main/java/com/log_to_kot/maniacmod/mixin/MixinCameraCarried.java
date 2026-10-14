package com.log_to_kot.maniacmod.mixin;


import com.log_to_kot.maniacmod.client.vr.VrCompat;
import com.log_to_kot.maniacmod.client.hooks.CarryPose;
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
 * Камера жертви на плечі маньяка йде за ІНТЕРПОЛЬОВАНИМ тілом носія (див. {@link CarryPose}), а не
 * за серверним сидінням, що оновлюється лише 20 раз на секунду: інакше свій погляд «смикався»,
 * хоча модель на екранах інших їхала рівно. Зсув, а не абсолютна позиція — тож працює й від третьої
 * особи. Той самий підхід, що {@code MixinCameraRopeHang}. {@code require = 0}: якщо мапінг
 * розійдеться, камера просто лишиться ванільною.
 */
@Mixin(Camera.class)
public abstract class MixinCameraCarried {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"), require = 0)
    private void maniacmod$followCarrier(BlockGetter level, Entity entity, boolean detached,
                                         boolean mirrored, float partialTick, CallbackInfo ci) {
        // VR: камера = голова гравця, зсув камери викликає нудоту.
        if (VrCompat.isVrActive()) return;
        Vec3 shift = CarryPose.cameraShift(entity, partialTick);
        if (shift == null) return;
        Vec3 p = ((Camera) (Object) this).getPosition();
        this.setPosition(p.x + shift.x, p.y + shift.y, p.z + shift.z);
    }
}
