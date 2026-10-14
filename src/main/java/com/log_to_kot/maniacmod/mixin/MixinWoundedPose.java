package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.vr.VrServerCompat;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.WoundedPose;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Поза «поранений» для виживого з поламаною ногою (див. {@link WoundedPose}).
 *
 * Той самий підхід, що й у {@code MixinWakeUpPose}/{@code MixinGeneratorWorkThirdPerson}:
 * {@code PlayerModel.setupAnim TAIL}. Ванільний setupAnim спершу малює гойдання
 * рук від ходьби, а ми після нього ПЕРЕПИСУЄМО кути рук цільовими — тому руки
 * фіксовані, поки гравець іде. Ноги не чіпаємо, ванільна хода лишається.
 *
 * Порядок відносно інших міксинів тут не важливий: {@link WoundedPose} сама
 * відступає для руки, яку веде ліхтар, підбір предмета, ремонт чи використання
 * предмета.
 *
 * {@code head/body.x/z} ванільний код не скидає, а модель одна на всіх гравців —
 * тому після позі їх треба повернути один раз (як у WakeUp).
 *
 * require = 0: якщо сигнатура setupAnim зміниться, поза просто не діятиме,
 * а гра не впаде.
 */
@Mixin(PlayerModel.class)
public abstract class MixinWoundedPose {

    /** Цей екземпляр моделі востаннє мав записані head/body.x/z. */
    @Unique
    private boolean maniacmod$woundedDirty;

    @Inject(method = "setupAnim", at = @At("TAIL"), require = 0)
    private void maniacmod$applyWounded(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                        float ageInTicks, float netHeadYaw, float headPitch,
                                        CallbackInfo ci) {
        if (entity instanceof net.minecraft.world.entity.player.Player vrp && VrServerCompat.isVrPlayer(vrp)) return;
        PlayerModel<?> m = (PlayerModel<?>) (Object) this;

        boolean wrote = false;
        if (entity instanceof Player player && !ClientMatchState.isProne(player.getUUID())) {
            wrote = WoundedPose.applyToModel(m, player);
        }

        if (wrote) {
            maniacmod$woundedDirty = true;
        } else if (maniacmod$woundedDirty) {
            WoundedPose.restorePersistentBase(m);
            maniacmod$woundedDirty = false;
        }
    }
}
