package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Глушить натискання ЛКМ маньяка, поки триває перезарядка удару.
 *
 * ── Навіщо міксин, якщо сервер і так лочить атаку ────────────────────
 * Сервер справді відхиляє удар через {@code LockType.ATTACK}
 * (shaurma-lib InteractionLock) — але це відбувається вже ПІСЛЯ того,
 * як клієнт програв замах руки і звук. Маньяк бачив би удар, якого не
 * сталося, і не розумів, чи влучив.
 *
 * Тому тут глушиться саме натискання: рука не рухається, звуку немає,
 * і перезарядка читається з поведінки, а не лише зі шкали HUD.
 *
 * ⚠ Це зручність, а не захист. Міксин живе на клієнті, і його можна
 * прибрати — удар усе одно не пройде, бо лок тримає сервер. Ніколи не
 * переносити сюди перевірки, від яких залежить чесність гри.
 */
@Mixin(Minecraft.class)
public abstract class ManiacAttackGuardMixin {

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void maniacmod$blockAttackDuringCooldown(CallbackInfoReturnable<Boolean> cir) {
        if (!ClientMatchState.isManiac()) return;

        // Фаза НЕ дозволяє бити (лобі, кіно, підсумки...) — глушимо клік
        // одразу, без огляду на кулдаун. Раніше тут стояв ранній return
        // на "не дозволено", який робив рівно навпаки: поза ігровими
        // фазами клік лишався непроглушеним (замах і звук програвались),
        // і глушився тільки кулдаун усередині самої гри.
        if (!ClientMatchState.allows(PhaseRule.DAMAGE)) {
            cir.setReturnValue(false);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        long tick = mc.level != null ? mc.level.getGameTime() : 0L;

        float left = ClientMatchState
            .abilityCooldownFraction(AbilityCooldownPacket.ATTACK_ID, tick);
        if (left <= 0f) return;

        // false = «клік не оброблено»: ні замаху, ні свінгу, ні звуку.
        cir.setReturnValue(false);
    }
}
