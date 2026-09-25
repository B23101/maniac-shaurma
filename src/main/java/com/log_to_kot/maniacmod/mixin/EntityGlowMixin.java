package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.glow.ClientEntityGlow;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Клієнтський міксин: дозволяє малювати ванільний контур світіння окремо
 * для КОЖНОГО гравця, а не для всіх одразу.
 *
 * ── Навіщо це взагалі ────────────────────────────────────────────────
 * Ванільне світіння — біт у метаданих сутності, який сервер розсилає
 * всім. Але САМ контур малюється на клієнті, і єдина умова малювання —
 * {@code Entity.isCurrentlyGlowing()}. Тому достатньо підмінити цей метод
 * локально: сутність, чий id є в {@link ClientEntityGlow}, світиться на
 * цьому клієнті, і лише на ньому. Жодних пакетів у метадані, жодного
 * впливу на маньяка чи серверну логіку.
 *
 * Кольори контуру ваніла бере з кольору команди
 * ({@code Entity.getTeamColor()}), тому його теж підміняємо — так колір
 * стану генератора (білий/жовтий/червоний/зелений) доходить до рендера,
 * лишаючись суто клієнтським.
 *
 * Міксин зареєстрований у секції {@code client} — на виділеному сервері
 * він не застосовується взагалі.
 */
@Mixin(Entity.class)
public abstract class EntityGlowMixin {

    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void maniacmod$perPlayerGlow(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (ClientEntityGlow.isGlowing(self.getId())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void maniacmod$perPlayerGlowColor(CallbackInfoReturnable<Integer> cir) {
        Entity self = (Entity) (Object) this;
        if (ClientEntityGlow.isGlowing(self.getId())) {
            cir.setReturnValue(ClientEntityGlow.color(self.getId()));
        }
    }
}
