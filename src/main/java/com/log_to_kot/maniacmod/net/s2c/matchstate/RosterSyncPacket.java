package com.log_to_kot.maniacmod.net.s2c.matchstate;

import com.log_to_kot.maniacmod.net.S2CPacket;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Сервер → клієнт: зведення по ВСІХ гравцях матчу (майбутній таб/скорборд).
 *
 * ── Категорія: matchstate, не roster ─────────────────────────────────
 * Це проєкція вже наявного стану матчу (хто є хто) без власної логіки
 * обчислення — саме той випадок, який {@code s2c/roster/README.md}
 * прямо передбачав як підставу лишити пакет у {@code matchstate}, а
 * порожню {@code roster/} — суто як місце для нотатки-правила, без
 * власного пакета.
 *
 * ── Правило, яке цей пакет виконує технічно, а не лише текстом ──────
 * Тут НЕМАЄ hp/stamina/heartbeat. Ці числа для ВЛАСНИКА вже летять
 * через {@link com.log_to_kot.maniacmod.net.s2c.vitals.SurvivorVitalsPacket}.
 * Роль власника — через {@link RoleSyncPacket}. RosterEntry несе лише
 * посилання (UUID → роль/стан), а не дублікат чисел, які вже десь
 * летять. Якщо колись знадобиться додати число в RosterEntry — це
 * свідомий крок, а не "про всяк випадок", і супроводжується
 * коментарем "чому тут, а не у vitals" (див. roster/README.md).
 *
 * Надсилається на ПОДІЮ (хтось приєднався/вибув/змінив стан), а не
 * щотік — та сама логіка, що вже є в SurvivorVitalsPacket.
 *
 * @param entries усі гравці матчу станом на момент відправки
 */
public record RosterSyncPacket(List<RosterEntry> entries) implements S2CPacket {

    /**
     * @param playerId    UUID гравця — посилання, не дублікат даних
     * @param displayName ім'я для відображення в таб/скорборді
     * @param role        роль гравця (та сама емуляція, що й RoleSyncPacket.Role)
     * @param state       стан виживого; для маньяка/глядача — HEALTHY (не читається)
     */
    public record RosterEntry(UUID playerId, String displayName,
                               RoleSyncPacket.Role role, SurvivorState state) {}

    public RosterSyncPacket(FriendlyByteBuf buf) {
        this(readEntries(buf));
    }

    private static List<RosterEntry> readEntries(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<RosterEntry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new RosterEntry(
                buf.readUUID(),
                buf.readUtf(),
                buf.readEnum(RoleSyncPacket.Role.class),
                buf.readEnum(SurvivorState.class)));
        }
        return list;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (RosterEntry e : entries) {
            buf.writeUUID(e.playerId());
            buf.writeUtf(e.displayName());
            buf.writeEnum(e.role());
            buf.writeEnum(e.state());
        }
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onRoster(entries);
    }
}
