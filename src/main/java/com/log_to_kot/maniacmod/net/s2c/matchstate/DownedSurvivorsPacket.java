package com.log_to_kot.maniacmod.net.s2c.matchstate;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Сервер → УСІМ клієнтам: хто зараз лежить непритомним, де і скільки йому
 * лишилось жити.
 *
 * ── Навіщо всім, а не лише виживим ───────────────────────────────────
 * Список потрібен для трьох речей, і не всі вони лише для виживих:
 *   1. ПОЗА. Непритомний лежить у пронизаній позі (див.
 *      {@code MixinPlayerDownedPose}). Клієнт САМ перераховує позу
 *      кожного гравця щотіка, тож без цього списку клієнт маньяка
 *      «підводив би» лежачих, і той бачив би їх стоячими.
 *   2. МІТКА на всю карту — для виживих і маньяка (дизайн: «його бачать
 *      і союзники, і маньяк»); глядачам {@code DownedSurvivorMarker} її не
 *      малює.
 *   3. Власний таймер до смерті на HUD непритомного.
 * Позиції лежачих і так видно в світі кожному, хто поруч, тож розсилка
 * їх усім нічого нового про перебіг гри не розкриває.
 *
 * ── Частота ──────────────────────────────────────────────────────────
 * Одразу при зміні складу списку (хтось ліг/підвівся/помер) і далі раз
 * на {@code SurvivorModule.DOWNED_BROADCAST_INTERVAL_TICKS} тіків, поки
 * список непорожній. Клієнт між пакетами сам відлічує час (так само, як
 * кулдауни): щотікового потоку немає.
 *
 * Порожній список — «нікого немає»: саме він гасить мітки й позу.
 *
 * @param entries лежачі виживі
 */
public record DownedSurvivorsPacket(List<Entry> entries) implements S2CPacket {

    /**
     * @param id        UUID лежачого
     * @param x         позиція тіла (для мітки на всю карту)
     * @param ticksLeft скільки тіків лишилось до смерті
     */
    public record Entry(UUID id, double x, double y, double z, int ticksLeft) {}

    public DownedSurvivorsPacket(FriendlyByteBuf buf) {
        this(read(buf));
    }

    private static List<Entry> read(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new Entry(buf.readUUID(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readVarInt()));
        }
        return list;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUUID(e.id());
            buf.writeDouble(e.x());
            buf.writeDouble(e.y());
            buf.writeDouble(e.z());
            buf.writeVarInt(e.ticksLeft());
        }
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onDownedSurvivors(entries);
    }
}
