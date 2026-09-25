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
 * ── Навіщо тут габарити маньяка ─────────────────────────────────────
 * Хітбокс і висота очей маньяка тепер налаштовуються в його власному
 * yml ({@code maniac_stats/<id>.yml}) і читаються з ОБОХ боків — на
 * сервері {@code ManiacBodyEvents}, на клієнті
 * {@code ClientManiacBodyEvents}. На виділеному сервері це два РІЗНІ
 * файли: клієнт створить собі свій з дефолтів і розійдеться з
 * сервером розмірами (чужий клієнт бив би в порожнечу повз триблочного
 * маньяка). Тому розміри, як і дальність удару в {@code RoleSyncPacket},
 * їдуть із сервера — джерелом правди лишається сервер, а локальний
 * конфіг працює лише як фолбек до першого ростеру.
 *
 * @param entries усі гравці матчу станом на момент відправки
 */
public record RosterSyncPacket(List<RosterEntry> entries) implements S2CPacket {

    /**
     * Габарити маньяка в грі: те, що сервер реально застосував до
     * гравця (тобто з його конфігу, а не з дефолтів схеми).
     *
     * @param width      ширина хітбокса
     * @param height     висота хітбокса
     * @param eyeHeight  висота очей
     */
    public record ManiacBody(float width, float height, float eyeHeight) {}

    /**
     * @param playerId    UUID гравця — посилання, не дублікат даних
     * @param displayName ім'я для відображення в таб-таблиці
     * @param role        роль гравця (та сама емуляція, що й RoleSyncPacket.Role)
     * @param archetypeId маньяк: id архетипу ({@code "test_maniac"}) — таб показує, ЯКИЙ саме маньяк;
     *                    виживий/глядач: порожній рядок
     * @param state       стан виживого (HEALTHY/.../ELIMINATED/ESCAPED);
     *                    для маньяка/глядача — HEALTHY (не читається)
     * @param hp          поточне хп виживого; для маньяка/глядача і для
     *                    ELIMINATED/ESCAPED (уже вибув) — 0, не читається
     * @param maxHp       максимум хп виживого; той самий виняток, що й hp
     * @param maniacBody  габарити маньяка; null для виживих і глядачів —
     *                    саме null, а не три нулі: відсутність габаритів
     *                    і нульовий хітбокс — різні речі, і клієнт не має
     *                    випадково застосувати другий
     */
    public record RosterEntry(UUID playerId, String displayName,
                               RoleSyncPacket.Role role, String archetypeId,
                               SurvivorState state, int hp, int maxHp,
                               ManiacBody maniacBody) {}

    public RosterSyncPacket(FriendlyByteBuf buf) {
        this(readEntries(buf));
    }

    private static List<RosterEntry> readEntries(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<RosterEntry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            RosterEntry entry = new RosterEntry(
                buf.readUUID(),
                buf.readUtf(),
                buf.readEnum(RoleSyncPacket.Role.class),
                buf.readUtf(),
                buf.readEnum(SurvivorState.class),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean()
                    ? new ManiacBody(buf.readFloat(), buf.readFloat(), buf.readFloat())
                    : null);
            list.add(entry);
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
            buf.writeBoolean(e.maniacBody() != null);
            if (e.maniacBody() != null) {
                buf.writeFloat(e.maniacBody().width());
                buf.writeFloat(e.maniacBody().height());
                buf.writeFloat(e.maniacBody().eyeHeight());
            }
        }
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onRoster(entries);
    }
}
