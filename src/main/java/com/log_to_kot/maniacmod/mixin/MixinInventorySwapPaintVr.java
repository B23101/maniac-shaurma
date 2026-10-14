package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.vr.VrCompat;
import dev.shaurmalib.forge.network.packets.InventorySlotAllocationPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * VR: кнопки «наступний/попередній слот» ідуть через {@code Inventory.swapPaint},
 * а не через подію скролу миші.
 *
 * ── Проблема ─────────────────────────────────────────────────────────
 * shaurma-lib обробляє ТІЛЬКИ подію скролу ({@code onMouseScroll}) і перемикає
 * по колу між дозволеними слотами. Vivecraft ({@code MCVR}) викликає
 * {@code swapPaint} напряму, тому подія не спрацьовує: ванільний крок потрапляє
 * на недозволений слот, а страховка бібліотеки ({@code onClientTick}) відкочує
 * на найближчий дозволений — і на межі списку гравець застрягає (з останнього
 * дозволеного «далі» знову повертає на нього ж, по колу на перший не переходить).
 *
 * ── Що робимо ────────────────────────────────────────────────────────
 * Лише для локального гравця, лише у VR і лише при активному обмеженні слотів
 * замінюємо ванільний крок на той самий циклічний перехід по дозволених
 * слотах, що й у бібліотеці для скролу (напрям той самий: додатне значення —
 * назад). В інших випадках — ванілла без змін. Бібліотеку не чіпаємо.
 */
@Mixin(Inventory.class)
public abstract class MixinInventorySwapPaintVr {

    @Shadow public int selected;

    // require = 0 — як в інших міксинах проєкту: якщо ціль зміниться, ця VR-страховка
    // просто не застосується, а не завалить запуск гри.
    @Inject(method = "swapPaint", at = @At("HEAD"), cancellable = true, require = 0)
    private void maniac$cycleAllowedSlotsInVr(double direction, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        // Лише інвентар ЛОКАЛЬНОГО гравця (без @Shadow на player — менше точок відмови).
        if (mc.player == null || (Object) this != mc.player.getInventory()) return;
        if (!VrCompat.isVrActive()) return;
        if (!InventorySlotAllocationPacket.ClientHandler.isRestricted()) return;

        List<Integer> allowed = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            if (InventorySlotAllocationPacket.ClientHandler.isAllowed(slot)) allowed.add(slot);
        }
        if (allowed.isEmpty()) return; // нічого дозволеного — лишаємо ваніль

        int step = direction > 0 ? -1 : 1;
        int index = allowed.indexOf(this.selected);
        int next = index < 0
            ? (step > 0 ? 0 : allowed.size() - 1)
            : Math.floorMod(index + step, allowed.size());
        this.selected = allowed.get(next);
        ci.cancel();
    }
}
