package com.log_to_kot.maniacmod.items;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ВЛАСНА перезарядка предметів (не ванільна {@code ItemCooldowns}).
 *
 * ── Навіщо ───────────────────────────────────────────────────────────
 * Ванільна перезарядка малює білу «шторку» на іконці, і змінити її не можна
 * без міксина. Для предметів із власним візуалом (біта) перезарядку веде
 * сервер тут, а клієнт малює бар під предметом
 * ({@code ItemHudDecorator}).
 *
 * ── Канал даних — той самий, що в кулдаунів здібностей ────────────────
 * Нового пакета нема: сервер шле {@link AbilityCooldownPacket} з id
 * {@link AbilityCooldownPacket#itemId(String)} ОДИН раз при старті, клієнт
 * відраховує сам ({@code ClientMatchState.abilityCooldownFraction}). Кінець
 * перезарядки клієнт бачить сам, тож «ready»-пакет шлеться лише при
 * примусовому скиданні ({@link #clearAll}).
 *
 * ── Час — тіки сервера ───────────────────────────────────────────────
 * Ключ — UUID гравця + ключ предмета: перезарядка належить ТИПУ предмета
 * в гравця, а не конкретному стеку — підняв іншу біту, поки перша
 * перезаряджається, — лишається та сама перезарядка.
 */
public final class OwnCooldowns {

    private static final Map<UUID, Map<String, Long>> READY_AT = new HashMap<>();

    private OwnCooldowns() {}

    /** Запускає перезарядку й повідомляє клієнт. {@code ticks <= 0} — нічого не робить. */
    public static void start(ServerPlayer player, String key, int ticks) {
        if (ticks <= 0 || player.getServer() == null) return;
        long readyAt = player.getServer().getTickCount() + ticks;
        READY_AT.computeIfAbsent(player.getUUID(), k -> new HashMap<>()).put(key, readyAt);
        ModNetwork.toPlayer(player, new AbilityCooldownPacket(AbilityCooldownPacket.itemId(key), ticks));
    }

    /** Скільки тіків лишилось до готовності (0 — готово). */
    public static int ticksLeft(ServerPlayer player, String key) {
        if (player.getServer() == null) return 0;
        Map<String, Long> mine = READY_AT.get(player.getUUID());
        if (mine == null) return 0;
        Long readyAt = mine.get(key);
        if (readyAt == null) return 0;
        long left = readyAt - player.getServer().getTickCount();
        if (left <= 0) {
            mine.remove(key);
            return 0;
        }
        return (int) left;
    }

    public static boolean isOnCooldown(ServerPlayer player, String key) {
        return ticksLeft(player, key) > 0;
    }

    /**
     * Скидає всі власні перезарядки й каже клієнтам, що все готово (без
     * анімації «готово» — клієнт просто прибирає бар). Викликати на межі
     * матчу, щоб перезарядка не переживала рестарт.
     */
    public static void clearAll(List<ServerPlayer> online) {
        if (READY_AT.isEmpty()) return;
        for (ServerPlayer player : online) {
            Map<String, Long> mine = READY_AT.get(player.getUUID());
            if (mine == null) continue;
            for (String key : mine.keySet()) {
                ModNetwork.toPlayer(player, AbilityCooldownPacket.ready(AbilityCooldownPacket.itemId(key)));
            }
        }
        READY_AT.clear();
    }
}
