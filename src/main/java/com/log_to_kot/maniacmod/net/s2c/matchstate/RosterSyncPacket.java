package com.log_to_kot.maniacmod.net.s2c.matchstate;

import com.log_to_kot.maniacmod.net.S2CPacket;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Сервер → клієнт: зведення по ВСІХ гравцях матчу для tab-екрана
 * (утримання Tab), реалізованого в
 * {@code client/overlay/roster/TabRosterOverlay}.
 *
 * ── Категорія: matchstate, не roster ─────────────────────────────────
 * Це проєкція вже наявного стану матчу (хто є хто) без власної логіки
 * обчислення — саме той випадок, який {@code s2c/roster/README.md}
 * прямо передбачав як підставу лишити пакет у {@code matchstate}, а
 * порожню {@code roster/} — суто як місце для нотатки-правила, без
 * власного пакета.
 *
 * ── Свідомий виняток із правила "не дублюй числа з vitals" ──────────
 * {@link com.log_to_kot.maniacmod.net.s2c.vitals.SurvivorVitalsPacket}
 * несе hp ЛИШЕ власнику — для власного HUD. Tab показує hp ІНШИМ
 * гравцям (усій команді виживих одночасно), а це інший глядач і інша
 * причина існування числа: vitals лишається джерелом правди для "мій
 * хп на екрані", RosterEntry — для "хп тіммейта в таблиці". Це не
 * випадковий дубль (той, проти якого застережено вище) — це свідомо
 * заведене поле під нову, раніше не потрібну функцію. Немає жодного
 * коду, що читає чуже hp з іншого місця: hp у RosterEntry — ЄДИНЕ
 * джерело для tab-екрана. stamina/heartbeat так само НЕ додаються
 * сюди: їх tab не показує, тому дублювати нема причини.
 *
 * Надсилається на ПОДІЮ (хтось приєднався/вибув/змінив стан/хп), а не
 * щотік — та сама логіка, що вже є в SurvivorVitalsPacket.
 *
 * @param entries усі гравці матчу станом на момент відправки
 */
public record RosterSyncPacket(List<RosterEntry> entries) implements S2CPacket {

    /**
     * @param playerId    UUID гравця — посилання, не дублікат даних
     * @param displayName ім'я для відображення в таб-таблиці
     * @param role        роль гравця (та сама емуляція, що й RoleSyncPacket.Role)
     * @param archetypeId маньяк: id архетипу ("chucky") — таб показує, ЯКИЙ саме маньяк;
     *                    виживий/глядач: порожній рядок
     * @param state       стан виживого (HEALTHY/.../ELIMINATED/ESCAPED);
     *                    для маньяка/глядача — HEALTHY (не читається)
     * @param hp          поточне хп виживого; для маньяка/глядача і для
     *                    ELIMINATED/ESCAPED (уже вибув) — 0, не читається
     * @param maxHp       максимум хп виживого; той самий виняток, що й hp
     */
    public record RosterEntry(UUID playerId, String displayName,
                               RoleSyncPacket.Role role, String archetypeId,
                               SurvivorState state, int hp, int maxHp) {}

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
                buf.readUtf(),
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
            buf.writeUtf(e.archetypeId());
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
