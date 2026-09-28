package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.ClientInputHandler;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Клієнтська частина «працюю з генератором»: рух і стрибок (ТЗ 12).
 *
 * Поки гравець тримає Shift+ПКМ на генераторі (ремонт або залив
 * бензину), він повністю зафіксований на місці — інакше виходило б
 * «ремонтую на бігу»: серверний прогрес іде, а гравець тим часом
 * тікає від маньяка. Друга половина вимоги (камера) лежить у
 * {@code MixinMouseHandlerManiacStunned}/{@code MixinEntityTurnManiacStunned}
 * через спільний {@code ClientLookLock} — це різні точки вводу, тож і
 * місця різні.
 *
 * ── Джерело стану ────────────────────────────────────────────────────
 * {@code ClientInputHandler.isRepairHeld()} — ЛОКАЛЬНИЙ прапор клієнта:
 * він стає {@code true} рівно тоді, коли гравець почав утримання на
 * справжньому генераторі (рейкаст по {@code GeneratorEntity}), і
 * скидається при відпусканні, у меню та при відкритті міні-гри. Це НЕ
 * самі клавіші: просто затиснуті Shift і ПКМ десь у полі камеру й рух не
 * блокують.
 *
 * ── Чому саме {@code KeyboardInput.tick}, як і в сусідніх станах ─────
 * Це єдине місце, де натиснуті клавіші перетворюються на вхід руху.
 * Обнулення після оригінального методу ({@code RETURN}) закриває всі
 * способи руху одразу (ходьбу, стрибок, присідання-повзання) і не
 * залежить від приватних методів {@code LocalPlayer}.
 *
 * ⚠ Зручність, а не захист: справжня фіксація — серверний
 * {@code LockType.MOVEMENT}, який ставить {@code GeneratorModule} у
 * {@code markWorking(...)}. Цей міксин лише прибирає кадр-два
 * «прослизання», поки до клієнта доїде серверний ресинк позиції (той
 * самий розрив, що описаний у {@link MixinKeyboardInputDownedMovement}).
 *
 * Присідання (Shift) НЕ чіпаємо: воно й так затиснуте — саме ним
 * гравець і починає взаємодію, а його скасування миттєво перервало б
 * утримання.
 */
@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInputGeneratorWork extends Input {

    @Inject(method = "tick", at = @At("RETURN"))
    private void maniacmod$restrictMovementWhileWorking(boolean isSneaking, float sneakSpeed, CallbackInfo ci) {
        if (!ClientInputHandler.isRepairHeld()) return;

        this.up = false;
        this.down = false;
        this.left = false;
        this.right = false;
        this.jumping = false;
        this.forwardImpulse = 0.0F;
        this.leftImpulse = 0.0F;
    }
}
