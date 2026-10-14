package com.log_to_kot.maniacmod.client.hooks;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.glow.HookHighlightGlow;
import com.log_to_kot.maniacmod.entity.VictimSeatEntity;
import com.log_to_kot.maniacmod.hooks.HookStatus;
import com.log_to_kot.maniacmod.net.s2c.hooks.HookHighlightPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Клієнтська частина гаків:
 * <ul>
 *   <li>вільні гаки, які сервер показує ЛИШЕ маньяку, що несе жертву: контур (коли сутність
 *       у зоні видимості) + екранна мітка {@link HookMarker} (на будь-якій відстані);</li>
 *   <li>поворот ТІЛА жертви, що сидить на плечі/гаку, на кут із сервера
 *       ({@code VictimSeatEntity#bodyYaw}) — ванільний пасажир крутить тіло за камерою.</li>
 * </ul>
 * Нічого не вирішує в матчі: чисто відображення.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientHooks {

    /** Колір контуру вільного гака (фіолетовий — той самий, що «несуть»). */
    private static final int FREE_HOOK_RGB = 0xC060FF;

    private static List<HookHighlightPacket.Entry> free = List.of();

    private ClientHooks() {}

    public static void onHighlight(List<HookHighlightPacket.Entry> entries) {
        // Роль могла змінитись — чужій ролі гаки не світимо.
        free = ClientMatchState.isManiac() ? List.copyOf(entries) : List.of();
        syncGlow();
    }

    public static List<HookHighlightPacket.Entry> freeHooks() {
        return free;
    }

    public static void reset() {
        free = List.of();
        HookHighlightGlow.clear();
    }

    private static void syncGlow() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || free.isEmpty()) {
            HookHighlightGlow.clear();
            return;
        }
        Map<Integer, Integer> next = new HashMap<>();
        for (HookHighlightPacket.Entry e : free) {
            // Сутність, якої клієнт ще не має (далеко), просто пропускаємо — лишається мітка.
            if (mc.level.getEntity(e.entityId()) != null) next.put(e.entityId(), FREE_HOOK_RGB);
        }
        HookHighlightGlow.set(next);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!free.isEmpty()) reset();
            return;
        }
        if (!free.isEmpty()) syncGlow();

        for (AbstractClientPlayer player : mc.level.players()) {
            if (!(player.getVehicle() instanceof VictimSeatEntity seat)) continue;
            // На плечі тіло й камеру веде CarryRenderHandler (щокадру від носія); тут лише гак.
            if (seat.status() != com.log_to_kot.maniacmod.hooks.HookStatus.HOOKED) continue;
            float target = seat.bodyYaw();
            player.yBodyRot = target;
            // Свою голову не чіпаємо (камера); чужу вирівнюємо з тілом, щоб не крутилась навколо.
            if (player != mc.player) player.yHeadRot = target;
            if (Math.abs(wrapDiff(player.yBodyRotO, target)) > 90f) player.yBodyRotO = target;
        }
    }

    private static float wrapDiff(float a, float b) {
        return net.minecraft.util.Mth.wrapDegrees(b - a);
    }

    /** Чи гравець зараз їде на плечі/гаку (для пози). */
    public static HookStatus statusOf(AbstractClientPlayer player) {
        return ClientMatchState.hookStatusOf(player.getUUID());
    }
}
