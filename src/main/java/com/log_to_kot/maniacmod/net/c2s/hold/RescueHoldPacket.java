package com.log_to_kot.maniacmod.net.c2s.hold;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: гравець утримує ПКМ, дивлячись на непритомного (підняття).
 *
 * ── Категорія: hold ──────────────────────────────────────────────────
 * На відміну від {@code intent} (одноразове натискання), тут важливий
 * СТАН утримання: почав/відпустив. Якщо колись з'явиться друга подібна
 * механіка (тримати ПКМ, тримати клавішу) — вона йде сюди ж, а не в
 * intent, саме через це смислове розрізнення "натиснув" vs "тримаю".
 *
 * Надсилається ДВІЧІ за утримання — на початку (holding=true) і в
 * кінці (holding=false), а не щотік. Прогрес рахує сервер: він і так
 * тікає матч, а клієнту не можна довіряти лічильник у 8 секунд.
 *
 * Чому не щотік: у v3 схожа механіка (ломом по капкану) працювала на
 * «останній тік виклику» й розсинхронізовувалась при лагах — прогрес
 * то йшов, то ні. Тут сервер сам знає, що утримання триває, доки не
 * прийшов holding=false або гравець не відійшов.
 *
 * @param holding true — почав утримувати, false — відпустив
 */
public record RescueHoldPacket(boolean holding) implements ModPacket {

    public RescueHoldPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(holding);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onRescueHold(ModNetwork.sender(ctx), holding);
    }
}
