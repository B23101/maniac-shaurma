package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.MinigameJumpGuard;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Глушить стрибок одразу після міні-гри, поки гравець не відпустить
 * пробіл — див. {@link MinigameJumpGuard}. Рух (WASD) не чіпає.
 */
@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInputMinigameJump extends Input {

    @Inject(method = "tick", at = @At("RETURN"))
    private void maniacmod$suppressJumpAfterMinigame(boolean isSneaking, float sneakSpeed, CallbackInfo ci) {
        if (MinigameJumpGuard.shouldSuppressJump()) {
            this.jumping = false;
        }
    }
}
