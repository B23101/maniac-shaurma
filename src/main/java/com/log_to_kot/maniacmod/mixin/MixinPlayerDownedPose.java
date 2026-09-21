package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.survivors.DownedPose;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Кладе непритомного виживого на землю: примусова поза плавання
 * (горизонтально, хітбокс 0.6×0.6).
 *
 * ── Чому міксин, а не {@code setPose} ────────────────────────────────
 * {@code Player.updatePlayerPose} щотіка сам вирішує позу (стоїть,
 * присів, летить, плаває) і перезаписує все, що ми поставили ззовні.
 * Єдиний спосіб втримати позу — перехопити саме цей метод.
 *
 * ── Обидві сторони ───────────────────────────────────────────────────
 * Міксин загальний (не client-only): клієнт теж рахує позу щотіка й без
 * цього «підводив» би лежачого назад. Хто саме лежить, вирішує
 * {@link DownedPose#isDowned}.
 *
 * ── require = 0 ──────────────────────────────────────────────────────
 * Якщо назва методу в іншій версії мапінгів розійдеться, міксин мовчки не
 * застосується (гравець просто не лежатиме), а не покладе запуск гри
 * помилкою ін'єкції. Про це не можна дізнатись без запуску: перевіряти
 * в грі, що непритомний справді лежить.
 */
@Mixin(Player.class)
public abstract class MixinPlayerDownedPose {

    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniacmod$proneWhenDowned(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (DownedPose.isDowned(self)) {
            self.setPose(Pose.SWIMMING);
            ci.cancel();
        }
    }
}
