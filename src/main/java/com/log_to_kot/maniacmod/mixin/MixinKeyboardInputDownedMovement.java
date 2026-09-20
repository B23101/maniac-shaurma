package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Забирає в лежачого виживого (CRAWLING / UNCONSCIOUS) ходьбу, стрибок і
 * присідання на КЛІЄНТІ.
 *
 * ── Чому серверного локу недостатньо ─────────────────────────────────
 * {@code InteractionLockHooks} морозить гравця на сервері
 * ({@code setDeltaMovement(0,0,0)} щотік + ресинк позиції раз на 5
 * тіків). Але клієнт сам симулює рух і шле позицію серверу, тому між
 * ресинками лежачий гравець «прослизає» вперед, а стрибок клієнт
 * виконує локально, ще до того як сервер його відкине. Саме це й
 * спостерігалось: рух і стрибки не блокувались.
 *
 * ── Чому саме {@code KeyboardInput.tick} ─────────────────────────────
 * Це єдине місце, де натиснуті клавіші перетворюються на вхід руху
 * ({@code up/down/left/right/jumping/shiftKeyDown} і похідні
 * {@code forwardImpulse/leftImpulse}). Обнулення ПІСЛЯ оригінального
 * методу (RETURN) закриває всі способи руху одразу й не залежить від
 * приватних методів {@code LocalPlayer}, які міняються між версіями.
 *
 * Пробіл при цьому лишається читабельним для нас: вставання читає
 * стан клавіші напряму через {@code options.keyJump.isDown()}
 * ({@code ManiacKeybinds.isStandUpDown}), а не через {@code jumping},
 * тому обнулене {@code jumping} на вставання не впливає.
 *
 * ── Присідання (Shift) ───────────────────────────────────────────────
 * {@code shiftKeyDown} свідомо НЕ чіпаємо. Shift — це утримання
 * порятунку ({@code RescueHoldPacket}), яке читається напряму через
 * {@code options.keyShift.isDown()}, а не через це поле; обнулення
 * прапорця тут нічого б не дало для порятунку, зате могло б розійтися
 * з тим, що читають інші частини клієнта. Присідання лежачого гравця
 * руху не дає — рух уже обнулено.
 *
 * ⚠ Це зручність і синхронізація відчуттів, а не захист: клієнт можна
 * змінити, і чесність тримає сервер (freeze щотік у
 * {@code InteractionLockHooks}). Ніколи не переносити сюди перевірки,
 * від яких залежить чесність гри.
 */
@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInputDownedMovement extends Input {

    @Inject(method = "tick", at = @At("RETURN"))
    private void maniacmod$blockMovementWhenDowned(boolean isSneaking, float sneakSpeed, CallbackInfo ci) {
        if (!ClientMatchState.isSurvivor()) return;

        SurvivorState state = ClientMatchState.survivorState();
        if (state != SurvivorState.CRAWLING && state != SurvivorState.UNCONSCIOUS) return;

        this.up = false;
        this.down = false;
        this.left = false;
        this.right = false;
        this.jumping = false;
        this.forwardImpulse = 0.0F;
        this.leftImpulse = 0.0F;
    }
}
