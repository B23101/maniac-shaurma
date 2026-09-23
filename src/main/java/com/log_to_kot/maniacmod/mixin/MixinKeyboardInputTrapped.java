package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Клієнтська частина «затиснутий у капкані».
 *
 * ── Навіщо клієнтський блок, якщо сервер і так лочить рух ────────────
 * {@code TrapModule.trigger} ставить {@code LockType.MOVEMENT} через
 * {@code InteractionLockHooks} — сервер морозить гравця щотік
 * ({@code setDeltaMovement(0,0,0)} + ресинк позиції раз на кілька
 * тіків). Але клієнт сам симулює рух локально й шле позицію серверу,
 * тому між ресинками гравець «прослизає» вперед на кадр-два — той
 * самий розрив, що описаний у {@link MixinKeyboardInputDownedMovement}
 * для CRAWLING/UNCONSCIOUS. Капкан — той самий клас проблеми: жертва
 * має завмерти МИТТЄВО в момент захлопування, а не за пару тіків, коли
 * долетить серверний ресинк.
 *
 * ── Джерело стану ──────────────────────────────────────────────────
 * {@link ClientMatchState#isTrapped()} — дзеркало серверного
 * {@code TrapModule.isTrapped}, що приходить у кожному
 * {@code SurvivorVitalsPacket} (поле {@code trapped}). Як і решта
 * клієнтського стану, це ЛИШЕ читання того, що вже вирішив сервер:
 * тут нічого не вирішується, лише глушиться ввід.
 *
 * ── Що саме блокується ────────────────────────────────────────────
 * Лише рух і стрибок — так само, як CRAWLING. Капкан НЕ чіпає поворот
 * камери (на відміну від майбутнього «стану маньяка» від удару ломом,
 * де за дизайном блокується і огляд): жертва капкана лише не може
 * зрушити з місця, а роздивлятись навколо (наприклад, кликати ПКМ по
 * капкану чи гукати тімейта) їй ніхто не забороняє.
 *
 * ⚠ Це зручність і синхронізація відчуттів, а не захист. Серверний лок
 * тримає справжню позицію; зняти цей міксин можна, і гравець просто
 * знову побачить дрібне прослизання між ресинками, але вирватись із
 * капкана раніше часу не зможе.
 */
@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInputTrapped extends Input {

    @Inject(method = "tick", at = @At("RETURN"))
    private void maniacmod$restrictMovementWhenTrapped(boolean isSneaking, float sneakSpeed, CallbackInfo ci) {
        if (!ClientMatchState.isTrapped()) return;

        this.up = false;
        this.down = false;
        this.left = false;
        this.right = false;
        this.jumping = false;
        this.forwardImpulse = 0.0F;
        this.leftImpulse = 0.0F;
    }
}
