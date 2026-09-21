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
 * Клієнтська частина «лежання» виживого.
 *
 *   • CRAWLING (щойно впав) — забирає ВСЕ: ходьбу, стрибок. Гравець лежить
 *     на місці, доки не натисне пробіл потрібну кількість разів.
 *   • UNCONSCIOUS (0 хп) — забирає лише СТРИБОК. Непритомний ПОВЗЕ:
 *     напрямки лишаються, а швидкість ріже серверний модифікатор
 *     (див. {@code SurvivorModule.ensureCrawlSpeed}), який клієнт отримує
 *     зі стандартною синхронізацією атрибутів.
 *
 * ── Чому серверного локу недостатньо (для CRAWLING) ──────────────────
 * {@code InteractionLockHooks} морозить гравця на сервері
 * ({@code setDeltaMovement(0,0,0)} щотік + ресинк позиції раз на 5
 * тіків). Але клієнт сам симулює рух і шле позицію серверу, тому між
 * ресинками лежачий гравець «прослизає» вперед, а стрибок клієнт
 * виконує локально, ще до того як сервер його відкине.
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
 * ── Клавіші дій ──────────────────────────────────────────────────────
 * Присідання (Shift) і ПКМ свідомо НЕ чіпаємо: підняття читає ПКМ напряму
 * через {@code options.keyUse}, а не через це поле. Присідання лежачому
 * нічого не дає — поза йому примусово ставить {@code MixinPlayerDownedPose}.
 *
 * ⚠ Це зручність і синхронізація відчуттів, а не захист: клієнт можна
 * змінити. Швидкість повзання тримає сервер (атрибут), а CRAWLING —
 * freeze щотік у {@code InteractionLockHooks}. Ніколи не переносити сюди
 * перевірки, від яких залежить чесність гри.
 */
@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInputDownedMovement extends Input {

    @Inject(method = "tick", at = @At("RETURN"))
    private void maniacmod$restrictMovementWhenDowned(boolean isSneaking, float sneakSpeed, CallbackInfo ci) {
        if (!ClientMatchState.isSurvivor()) return;

        SurvivorState state = ClientMatchState.survivorState();
        if (state == SurvivorState.UNCONSCIOUS) {
            // Повзе: напрямки лишаються, стрибати не може.
            this.jumping = false;
            return;
        }
        if (state != SurvivorState.CRAWLING) return;

        this.up = false;
        this.down = false;
        this.left = false;
        this.right = false;
        this.jumping = false;
        this.forwardImpulse = 0.0F;
        this.leftImpulse = 0.0F;
    }
}
