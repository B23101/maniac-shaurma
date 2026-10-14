package com.log_to_kot.maniacmod.net.s2c.rating;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → клієнт: перелік маньяків для оцінки і збережені оцінки
 * гравця. Шлеться один раз при заході (див. {@code ManiacRatingHooks}).
 *
 * ── Категорія: rating ────────────────────────────────────────────────
 * Це ДАНІ профілю гравця, а не стан матчу й не подія в грі — тому
 * окрема категорія, а не {@code matchstate}: вони переживають матчі й
 * не скидаються {@code ClientMatchState.reset()}.
 *
 * ── Що всередині ─────────────────────────────────────────────────────
 *   • {@code everSubmitted} — чи гравець колись надсилав оцінки (від
 *     цього залежить, чи показувати екран оцінок при виході);
 *   • {@code entries} — КОЖЕН зареєстрований маньяк (нулі, якщо ще не
 *     оцінений), у стабільному порядку за id;
 *   • {@code comment} — минулий коментар, щоб його можна було правити.
 */
public record ManiacRatingSyncPacket(boolean everSubmitted,
                                     List<Entry> entries,
                                     String comment) implements S2CPacket {

    /**
     * Зарезервований id запису «вся гра в цілому» (критерії візуал / геймплей /
     * карта — як «режим» у снайперів). Не збігається з id жодного маньяка:
     * id архетипів — латиниця без підкреслення на початку.
     */
    public static final String GAME_ID = "_game";

    /** Захист від зламаного розміру списку при читанні. */
    private static final int MAX_ENTRIES = 128;
    public static final int MAX_COMMENT = 512;

    /** Одна оцінка: для маньяка — вигляд / здібності / страх, для {@link #GAME_ID} — візуал / геймплей / карта (0 = не виставлено). */
    public record Entry(String id, float look, float abilities, float fear) {}

    public ManiacRatingSyncPacket {
        entries = List.copyOf(entries);
        comment = comment == null ? "" : comment;
    }

    public ManiacRatingSyncPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), readEntries(buf), buf.readUtf(MAX_COMMENT));
    }

    private static List<Entry> readEntries(FriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<Entry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new Entry(buf.readUtf(64), buf.readFloat(), buf.readFloat(), buf.readFloat()));
        }
        return list;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(everSubmitted);
        int size = Math.min(entries.size(), MAX_ENTRIES);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            Entry e = entries.get(i);
            buf.writeUtf(e.id(), 64);
            buf.writeFloat(e.look());
            buf.writeFloat(e.abilities());
            buf.writeFloat(e.fear());
        }
        buf.writeUtf(comment, MAX_COMMENT);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onManiacRatings(this);
    }
}
