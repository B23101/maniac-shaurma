package com.log_to_kot.maniacmod.net.c2s.hold;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: гравець затиснув (або відпустив) цвях у міні-грі барикади.
 *
 * <p>Категорія {@code hold}: важливий СТАН утримання. Шлеться лише на зміну — «взяв цвях №N» і
 * «відпустив» ({@code nail = -1}), а не щотік; прогрес рахує сервер. Номер цвяха клієнту не
 * довіряється сліпо: сервер перевіряє діапазон, чи цвях ще не витягнутий і чи його не тримає
 * хтось інший.
 *
 * @param nail індекс цвяха ({@code дошка * 2 + кінець}) або {@code -1} — відпустив
 */
public record BarricadeNailHoldPacket(int nail) implements ModPacket {

    public BarricadeNailHoldPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt() - 1);
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(nail + 1);   // −1 → 0: VarInt не любить від'ємних
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onBarricadeNailHold(ModNetwork.sender(ctx), nail);
    }
}
