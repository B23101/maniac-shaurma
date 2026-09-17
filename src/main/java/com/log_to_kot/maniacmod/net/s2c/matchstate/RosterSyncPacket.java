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
 * Тут НЕМАЄ stamina/heartbeat — ці числа мають сенс лише для
 * ВЛАСНИКА і вже летять через
 * {@link com.log_to_kot.maniacmod.net.s2c.vitals.SurvivorVitalsPacket}.
 * Роль власника — через {@link RoleSyncPacket}. Дублювати їх тут не
 * можна: два пакети з однаковими числами — два джерела правди.
 *
 * hp/maxHp — виняток, доданий свідомо (не "про всяк випадок"): це
 * єдине число, яке команді треба бачити ПРО ІНШИХ (tab-екран:
 * "живі виживші бачать хп кожного тіммейта"), а vitals у принципі не
 * може його нести — vitals показує лише показники власного гравця,
 * а не чужі. Тому це не дублікат, а нові дані для нового глядача
 * (командний огляд, а не власний HUD).
 *
 * Надсилається на ПОДІЮ (хтось приєднався/вибув/змінив стан чи хп), а
 * не щотік — та сама логіка, що вже є в SurvivorVitalsPacket.
 *
 * @param entries усі гравці матчу станом на момент відправки
 */
public record RosterSyncPacket(List<RosterEntry> entries) implements S2CPacket {

    /**
     * @param playerId    UUID гравця — посилання, не дублікат даних
     * @param displayName ім'я для відображення в таб/скорборді
     * @param role        роль гравця (та сама емуляція, що й RoleSyncPacket.Role)
     * @param state       стан виживого; для маньяка/глядача — HEALTHY (не читається)
     * @param hp          поточне хп; для маньяка/глядача — 0 (не читається)
     * @param maxHp       максимум хп; для маньяка/глядача — 0 (не читається)
     */
    public record RosterEntry(UUID playerId, String displayName,
                               RoleSyncPacket.Role role, SurvivorState state,
                               int hp, int maxHp) {}

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
                buf.readEnum(SurvivorState.class),
                buf.readVarInt(),
                buf.readVarInt()));
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
            buf.writeVarInt(e.hp());
            buf.writeVarInt(e.maxHp());
        }
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onRoster(entries);
    }
}
