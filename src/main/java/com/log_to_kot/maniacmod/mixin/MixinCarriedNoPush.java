package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.entity.VictimSeatEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Жертва на плечі/гаку НЕ штовхається ні з ким — і, головне, не штовхає маньяка.
 *
 * ── Баг, який це лагодить ────────────────────────────────────────────
 * Жертва їде на {@link VictimSeatEntity}, а сидіння стоїть на плечі маньяка, тобто ХІТБОКСИ
 * жертви й маньяка перетинаються. Ванільна фізика штовхає гравців, що перетинаються, на кожному
 * тіку, тож маньяка постійно зносило вбік («заносить кудись»), хоч сидіння саме й не штовхабельне.
 *
 * ── Як ───────────────────────────────────────────────────────────────
 *   • {@code isPushable()} = false — жертву не штовхають інші (у т.ч. маньяк);
 *   • {@code pushEntities()} скасовано — жертва не штовхає інших.
 * Діє лише поки гравець їде на сидінні в статусі CARRIED/HOOKED; у решту часу фізика ванільна.
 * Міксин спільний (клієнт + сервер): маньяк рухається клієнтською передбачуваністю, тож
 * прибрати штовхання треба і там.
 */
@Mixin(LivingEntity.class)
public abstract class MixinCarriedNoPush {

    private boolean maniacmod$isSeatedVictim() {
        LivingEntity self = (LivingEntity) (Object) this;
        return self.getVehicle() instanceof VictimSeatEntity seat && seat.status().isSeated();
    }

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void maniacmod$seatedNotPushable(CallbackInfoReturnable<Boolean> cir) {
        if (maniacmod$isSeatedVictim()) cir.setReturnValue(false);
    }

    @Inject(method = "pushEntities", at = @At("HEAD"), cancellable = true)
    private void maniacmod$seatedDoesNotPush(CallbackInfo ci) {
        if (maniacmod$isSeatedVictim()) ci.cancel();
    }
}
