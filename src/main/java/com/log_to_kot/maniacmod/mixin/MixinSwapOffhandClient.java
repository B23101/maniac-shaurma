package com.log_to_kot.maniacmod.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Клієнтський блок обміну предметів між руками (ванільний F).
 *
 * ── Навіщо ───────────────────────────────────────────────────────────
 * У моді немає слота лівої руки: інвентар розподілений власним
 * менеджером слотів, а оффхенд у геймплеї не передбачений. Ванільний
 * F дозволяв би перекинути предмет у слот, якого «немає», — це
 * нечесна перевага (напр. тримати ліхтарик і лом одночасно).
 *
 * ── Як ───────────────────────────────────────────────────────────────
 * Перед {@code handleKeybinds} гасимо всі накопичені кліки
 * {@code keySwapOffhand}: ванільний код тоді не бачить натискання й
 * не шле {@code ServerboundPlayerActionPacket}. Блок ДЕЙСНИЙ ЗАВЖДИ
 * (не лише в капкані): F тепер належить моду — див.
 * {@code ManiacKeybinds.TRAP_ESCAPE}, яка читає СВІЙ {@code KeyMapping},
 * а не цей.
 *
 * ⚠ Це зручність, а не захист: модифікований клієнт може надіслати
 * пакет напряму. Справжній блок — {@link MixinSwapOffhandServer}.
 */
@Mixin(Minecraft.class)
public abstract class MixinSwapOffhandClient {

    @Shadow public net.minecraft.client.Options options;

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void maniacmod$dropSwapOffhandClicks(CallbackInfo ci) {
        KeyMapping swap = this.options.keySwapOffhand;
        while (swap.consumeClick()) {
            // навмисно порожньо: клік з'їдено, пакет не піде
        }
    }
}
