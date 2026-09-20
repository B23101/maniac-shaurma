package com.log_to_kot.maniacmod.net.s2c.actionprogress;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: прогрес вставання після падіння.
 *
 * ── Категорія: actionprogress ────────────────────────────────────────
 * Це «прогрес дії, що триває зараз» — та сама категорія, що
 * {@link GeneratorProgressPacket}: гравець лежить, тисне пробіл, і
 * хоче бачити, скільки ще лишилось. Це НЕ {@code vitals}: vitals — це
 * показники самого тіла (хп/стаміна/стан), що існують увесь матч, а
 * шкала вставання живе лише кілька секунд поки триває CRAWLING.
 *
 * ── Чому окремий пакет, а не поле в SurvivorVitalsPacket ─────────────
 * Vitals летять кожного разу, коли міняється стаміна (тобто майже
 * щотік під час бігу). Якщо додати сюди лічильник вставання, кожна
 * зміна стаміни тягнула б за собою зайві байти, а кожне натискання
 * пробілу — повний знімок vitals. Дві незалежні частоти змін —
 * два окремі пакети.
 *
 * ── Що шле сервер, а що рахує клієнт ─────────────────────────────────
 * Сервер — джерело правди: він шле ЛИШЕ на зміну (падіння, кожне
 * натискання, вставання). Клієнт нічого не вгадує і не «докручує»
 * шкалу сам, бо кожне натискання й так приходить окремим пакетом.
 *
 * @param presses  скільки натискань уже зараховано
 * @param required скільки треба всього; 0 = вставання не триває
 *                 (шкалу треба сховати)
 */
public record StandUpProgressPacket(int presses, int required) implements S2CPacket {

    /** Вставання завершено/скасовано — клієнт ховає шкалу. */
    public static StandUpProgressPacket none() {
        return new StandUpProgressPacket(0, 0);
    }

    public StandUpProgressPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(presses);
        buf.writeVarInt(required);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onStandUpProgress(presses, required);
    }
}
