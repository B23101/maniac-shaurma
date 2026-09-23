package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Клієнтська частина «оглушений маньяк»: рух і стрибок.
 *
 * Той самий підхід, що {@code MixinKeyboardInputTrapped} для капкана —
 * дублює на клієнті те, що серверний {@code LockType.MOVEMENT} уже
 * робить примусово ({@code ManiacStunModule#beginStun}), щоб маньяк
 * завмер миттєво в момент удару, а не за кадр-два, поки долетить
 * серверний ресинк позиції.
 *
 * Джерело стану — {@link ClientMatchState#isManiacStunned()}: це ЛИШЕ
 * читання того, що вже вирішив сервер (прийшло через
 * {@code ManiacStunPacket}), нічого тут не вирішується.
 *
 * ⚠ Зручність, а не захист: серверний лок тримає справжню позицію
 * незалежно від цього міксина.
 */
@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInputManiacStunned extends Input {

    @Inject(method = "tick", at = @At("RETURN"))
    private void maniacmod$restrictMovementWhenStunned(boolean isSneaking, float sneakSpeed, CallbackInfo ci) {
        if (!ClientMatchState.isManiacStunned()) return;

        this.up = false;
        this.down = false;
        this.left = false;
        this.right = false;
        this.jumping = false;
        this.forwardImpulse = 0.0F;
        this.leftImpulse = 0.0F;
    }
}
