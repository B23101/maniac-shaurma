package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.ClientInputHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Глушить ванільний замах рукою під час ремонту генератора.
 *
 * ── Звідки береться замах, якого ми не малювали самі ──────────────────
 * Ремонт визначається утриманням ПКМ ({@code ManiacKeybinds.isRepairHeld},
 * Shift+ПКМ), а {@code GeneratorEntity.interact} повертає PASS/SUCCESS і
 * нічого не робить сам — тобто ванільний клієнт весь час бачить фізично
 * затиснуту {@code keyUse} над сутністю без реальної дії у відповідь, і
 * щотіка намагається "використати предмет" на ній: {@code Minecraft.startUseItem}
 * програє замах руки й відповідну анімацію заново, поки кнопка тримається.
 * При ремонті на 90 секунд це постійний smear-рух руки, що заважає бачити
 * саму сутність.
 *
 * ── void, а не boolean ─────────────────────────────────────────────────
 * На відміну від {@code startAttack} (яку глушить {@code ManiacAttackGuardMixin}
 * через {@code CallbackInfoReturnable<Boolean>}), {@code startUseItem} у
 * 1.20.1 повертає {@code void} — тому тут звичайний {@link CallbackInfo}
 * і {@code cir.cancel()}, а не {@code setReturnValue}.
 *
 * ── Чому саме тут, а не заборона interact на сервері ──────────────────
 * Серверна частина взаємодії тут ні до чого: сервер узагалі не бачить
 * "клік" — ремонт іде окремим {@code GeneratorRepairHoldPacket} на зміну
 * стану (див. {@code GeneratorModule}). Замах — суто клієнтська, візуальна
 * дія, тому й глушити її треба на клієнті.
 *
 * ── Коли саме глушимо ──────────────────────────────────────────────────
 * Лише поки {@link ClientInputHandler#isRepairHeld()} — тобто ремонт УЖЕ
 * розпізнано й активний. Перший клік/дотик, яким гравець тільки починає
 * тримати Shift+ПКМ, не чіпаємо: він і так один раз, не серія.
 *
 * ⚠ Це суто візуальна заглушка. Прогрес ремонту тримає сервер
 * (GeneratorRepairHoldPacket), і від цього міксина ніяк не залежить.
 */
@Mixin(Minecraft.class)
public abstract class GeneratorRepairSwingGuardMixin {

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void maniacmod$suppressSwingDuringRepair(CallbackInfo ci) {
        if (!ClientInputHandler.isRepairHeld()) return;
        ci.cancel();
    }
}