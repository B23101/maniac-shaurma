package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.WakeUpAnimation;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Поза анімації пробудження ({@code wake_up.json}) на {@code PlayerModel}.
 *
 * Той самий шлях, що в лежачого і генератора ({@code MixinDownedPoseThirdPerson},
 * {@code MixinGeneratorWorkThirdPerson}): запис у {@code setupAnim TAIL},
 * останнім, щоб ванільна локомоція не перебила кліп. Працює для КОЖНОГО
 * гравця, якого видно, бо годинник загальний ({@code WakeUpAnimation}).
 *
 * Уся логіка (яка кістка, які числа, коли) — у {@link WakeUpAnimation};
 * тут лише виклик і «прибирання за собою»: модель одна на всіх гравців, а
 * ванільний {@code setupAnim} не скидає {@code head.x/z}, {@code body.x/z}
 * і {@code leg.x}. Тому після анімації один раз повертаємо їх у норму (до
 * того, як цією моделлю намалюється наступний гравець).
 *
 * Лежачий ({@code isProne}) має свою позу — його не чіпаємо.
 *
 * require = 0 — якщо мапінг розійдеться, інжект мовчки не застосується.
 */
@Mixin(PlayerModel.class)
public abstract class MixinWakeUpPose {

    /** Цей екземпляр моделі щойно був позначений анімацією і потребує скидання бази. */
    @Unique
    private boolean maniacmod$wakeDirty;

    @Inject(method = "setupAnim", at = @At("TAIL"), require = 0)
    private void maniacmod$applyWakeUp(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                       float ageInTicks, float netHeadYaw, float headPitch,
                                       CallbackInfo ci) {
        PlayerModel<?> m = (PlayerModel<?>) (Object) this;

        boolean applied = entity instanceof Player player
            && !ClientMatchState.isProne(player.getUUID())
            && WakeUpAnimation.applyToModel(m, player.getUUID());

        if (applied) {
            maniacmod$wakeDirty = true;
        } else if (maniacmod$wakeDirty) {
            WakeUpAnimation.restorePersistentBase(m);
            maniacmod$wakeDirty = false;
        }
    }
}
