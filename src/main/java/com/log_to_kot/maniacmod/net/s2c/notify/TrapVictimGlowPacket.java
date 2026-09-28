package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт (усім, хто бачить рівень): яких виживих треба
 * підсвітити як «зараз у пастці» (капкан або електричний дріт).
 *
 * ── Чому не {@code setGlowingTag} ────────────────────────────────────
 * Ванільне світіння — біт у метаданих сутності, який сервер розсилає
 * ВСІМ; окремого «світити лише цим гравцям» немає. Тут же світіння
 * робить КЛІЄНТ через {@code ClientEntityGlow} + {@code EntityGlowMixin},
 * тому сервер лишається єдиним джерелом правди «хто в пастці», а сам
 * контур малює кожен клієнт локально.
 *
 * ── Чому ПОВНИЙ набір, а не add/remove ───────────────────────────────
 * Пакет несе АКТУАЛЬНИЙ склад підсвічених, а не дельту. Це той самий
 * підхід, що {@code TrapLoadoutPacket}/генератор-підсвітка: заміна
 * цілком ідемпотентна й не накопичує розсинхрон, якщо один
 * add/remove-пакет загубиться. Надсилається лише в моменти зміни
 * (хтось потрапив / звільнився), тож ціна — кілька пакетів за матч.
 *
 * @param color     ARGB-колір контуру (без альфи; нею рендер не
 *                  користується). Зараз — «тривожний» жовто-червоний.
 * @param entityIds id сутностей-жертв, які зараз у пастці. Порожній масив
 *                  = погасити підсвітку всім.
 */
public record TrapVictimGlowPacket(int color, int[] entityIds) implements S2CPacket {

    /** Колір контуру жертви в пастці — попереджувальний янтарний. */
    public static final int VICTIM_COLOR = 0xFFFF5A2A;

    public TrapVictimGlowPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarIntArray());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(color);
        buf.writeVarIntArray(entityIds);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onTrapVictimGlow(color, entityIds);
    }
}
