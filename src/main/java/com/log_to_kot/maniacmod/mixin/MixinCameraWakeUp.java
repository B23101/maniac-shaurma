package com.log_to_kot.maniacmod.mixin;


import com.log_to_kot.maniacmod.client.vr.VrCompat;
import com.log_to_kot.maniacmod.client.WakeUpAnimation;
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
 * Камера від першої особи йде за головою з анімації пробудження.
 *
 * ── Чому міксин, а не {@code EntityEvent.EyeHeight} ──────────────────
 * Подія висоти очей спрацьовує при перерахунку габаритів сутності, а не
 * щокадру, — камера рухалась би ривками раз на тік. {@code Camera.setup}
 * викликається щокадру й виставляє положення камери; додаємо зсув
 * одразу ПІСЛЯ нього. Поворот камери робить Forge-подія
 * ({@code WakeUpAnimation.onCameraAngles}), тут лише положення.
 *
 * Скільки зсунути — рахує {@code WakeUpAnimation.cameraShift}
 * ({@code HeadKinematics}); поза анімацією вона повертає {@code null} і
 * камера ванільна. Третя особа ({@code detached}) не чіпається.
 *
 * require = 0 — якщо мапінг розійдеться, інжект мовчки не застосується
 * (лишиться поворот без зсуву), а не впаде гра.
 */
@Mixin(Camera.class)
public abstract class MixinCameraWakeUp {

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"), require = 0)
    private void maniacmod$followAnimatedHead(BlockGetter level, Entity entity, boolean detached,
                                              boolean mirrored, float partialTick, CallbackInfo ci) {
        // VR: камера = голова гравця, зсув камери викликає нудоту.
        if (VrCompat.isVrActive()) return;
        if (detached) return;
        Vec3 shift = WakeUpAnimation.cameraShift(entity, partialTick);
        if (shift == null) return;

        Vec3 p = ((Camera) (Object) this).getPosition();
        this.setPosition(p.x + shift.x, p.y + shift.y, p.z + shift.z);
    }
}
