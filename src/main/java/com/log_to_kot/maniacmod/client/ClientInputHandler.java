package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.intent.AbilityActivatePacket;
import com.log_to_kot.maniacmod.net.c2s.intent.HighlightTogglePacket;
import com.log_to_kot.maniacmod.net.c2s.hold.RescueHoldPacket;
import com.log_to_kot.maniacmod.net.c2s.intent.StandUpPacket;
import com.log_to_kot.maniacmod.net.c2s.intent.TrapPlacePacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Читає клавіші й перетворює їх на C2S-пакети.
 *
 * ── Принцип ──────────────────────────────────────────────────────────
 * Клієнт надсилає НАМІР, а не результат. Тутешні перевірки (роль,
 * фаза, стан) — лише щоб не засмічувати мережу свідомо марними
 * пакетами; сервер усе одно перевіряє все заново, бо клієнт може
 * бути модифікований.
 *
 * ── Утримання Shift ──────────────────────────────────────────────────
 * Надсилається двома пакетами — на початку й у кінці утримання, а не
 * щотік. Поле rescueHeld тримає локальний стан, щоб зафіксувати
 * момент зміни.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientInputHandler {

    private static boolean rescueHeld = false;

    private ClientInputHandler() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            // Вийшли зі світу — скидаємо все, інакше наступний матч
            // почнеться з чужим станом (v3-баг із залиплими оверлеями).
            if (rescueHeld) rescueHeld = false;
            return;
        }
        if (mc.screen != null) return; // відкрите меню — клавіші не наші

        handleAbilities();
        handleTraps();
        handleHighlight();
        handleStandUp();
        handleRescueHold();
    }

    /** Клавіші 1, 2, 3 — здібності маньяка. */
    private static void handleAbilities() {
        if (!ClientMatchState.isManiac()) return;
        if (!ClientMatchState.allows(PhaseRule.ABILITIES)) return;

        for (int slot = 0; slot < ManiacKeybinds.ABILITIES.length; slot++) {
            boolean pressed = false;
            while (ManiacKeybinds.ABILITIES[slot].consumeClick()) pressed = true;
            if (!pressed) continue;

            // Локальна перевірка кулдауну — щоб не слати пакет, який
            // сервер усе одно відхилить. Сам кулдаун тримає сервер.
            if (onCooldown(AbilityCooldownPacket.abilityId(slot))) continue;
            ModNetwork.toServer(new AbilityActivatePacket(slot));
        }
    }

    /** Клавіші Z, X, C — пастки маньяка. */
    private static void handleTraps() {
        if (!ClientMatchState.isManiac()) return;
        if (!ClientMatchState.allows(PhaseRule.TRAPS)) return;

        for (int slot = 0; slot < ManiacKeybinds.TRAPS.length; slot++) {
            boolean pressed = false;
            while (ManiacKeybinds.TRAPS[slot].consumeClick()) pressed = true;
            if (!pressed) continue;

            if (onCooldown(AbilityCooldownPacket.trapId(slot))) continue;
            ModNetwork.toServer(new TrapPlacePacket(slot));
        }
    }

    private static boolean onCooldown(String actionId) {
        Minecraft mc = Minecraft.getInstance();
        long tick = mc.level != null ? mc.level.getGameTime() : 0L;
        return ClientMatchState.abilityCooldownFraction(actionId, tick) > 0f;
    }

    private static void handleHighlight() {
        if (!ClientMatchState.isSurvivor()) return;
        if (!ClientMatchState.allows(PhaseRule.HUD)) return;

        while (ManiacKeybinds.HIGHLIGHT.consumeClick()) {
            ModNetwork.toServer(new HighlightTogglePacket());
        }
    }

    /** Пробіл, поки гравець збитий з ніг. */
    private static void handleStandUp() {
        if (!ClientMatchState.isSurvivor()) return;
        if (!ClientMatchState.allows(PhaseRule.MOVEMENT)) return;
        if (ClientMatchState.survivorState() != SurvivorState.CRAWLING) return;

        if (ManiacKeybinds.isStandUpDown()) {
            ModNetwork.toServer(new StandUpPacket());
        }
    }

    /** Shift біля непритомного — лише зміни стану, не щотік. */
    private static void handleRescueHold() {
        if (!ClientMatchState.isSurvivor()) return;
        if (!ClientMatchState.allows(PhaseRule.RESCUE)) {
            if (rescueHeld) {
                rescueHeld = false;
                ModNetwork.toServer(new RescueHoldPacket(false));
            }
            return;
        }

        boolean held = ManiacKeybinds.isRescueHeld();
        if (held == rescueHeld) return;

        rescueHeld = held;
        ModNetwork.toServer(new RescueHoldPacket(held));
    }
}
