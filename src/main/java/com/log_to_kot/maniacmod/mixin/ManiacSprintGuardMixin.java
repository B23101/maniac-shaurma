package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Маньяк НЕ спринтує взагалі — ні подвійним W, ні клавішею спринту.
 *
 * ── Навіщо клієнтський міксин, якщо сервер і так знімає спринт ───────
 * {@code ManiacSpeedModule} щотіку на сервері кличе
 * {@code setSprinting(false)}, тож спринт ПОВЕРХ множника швидкості
 * ходьби ({@code speedMultiplier()}) уже неможливий — фізично зайвий
 * тік спринту не проживе довше одного серверного тіку. Але клієнт сам
 * локально стартує спринт-анімацію й FOV ще до того, як прийде
 * корекція від сервера — маньяк бачив би "смикання" FOV/анімації
 * щоразу, як спробує подвійний W. Цей міксин прибирає сам ефект у
 * джерелі: {@code setSprinting} ніколи не переходить у {@code true}
 * для маньяка, тому й немає що коригувати.
 *
 * ── Чому ціль {@code LivingEntity}, а не {@code Player} ───────────────
 * Спринт-логіка ({@code isSprinting}/{@code setSprinting}, атрибут
 * {@code SPRINTING_SPEED_BOOST}) належить саме {@link LivingEntity} —
 * базовому класу {@code Player}, а не самому {@code Player}. Mixin
 * шукає метод у байткоді класу-цілі, тож ціллю має бути клас, де метод
 * РЕАЛЬНО оголошений, а не проміжний нащадок.
 *
 * ── Чому саме тут, а не {@code KeyboardInput} ─────────────────────────
 * На відміну від {@code MixinKeyboardInputDownedMovement} (де рух
 * гаситься через сирі клавіші вводу), рішення "чи входити в спринт"
 * ухвалює сам гравець — {@code setSprinting(true)} викликається і з
 * клавіші подвійного W, і з {@code Options.keySprint}, і з мережі.
 * Перехопити саме тут — єдине місце, що покриває всі шляхи одразу.
 *
 * ⚠ Зручність, не захист: сервер однаково не дасть спринту тривати
 * довше тіку (див. {@code ManiacSpeedModule}), цей міксин лише прибирає
 * клієнтський візуальний "смик" ДО серверної корекції.
 */
@Mixin(LivingEntity.class)
public abstract class ManiacSprintGuardMixin {

    @Inject(method = "setSprinting", at = @At("HEAD"), cancellable = true)
    private void maniacmod$blockManiacSprint(boolean sprinting, CallbackInfo ci) {
        if (sprinting && ClientMatchState.isManiac()) {
            ci.cancel();
        }
    }
}
