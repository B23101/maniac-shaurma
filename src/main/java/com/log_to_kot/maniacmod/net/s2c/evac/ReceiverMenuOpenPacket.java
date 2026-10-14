package com.log_to_kot.maniacmod.net.s2c.evac;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт (лише тому, хто клацнув ПКМ по приймачу): відкрити меню.
 *
 * <p>Категорія — notify/діалог: разова подія, клієнт стан не запам'ятовує. {@code state}
 * вирішує СЕРВЕР: 0 — немає живлення, 1 — можна викликати, 2 — вертоліт уже викликано,
 * 3 — зараз недоступно. Кнопку «Викликати» клієнт вмикає лише при 1, але сервер при натисканні
 * перевіряє все ще раз ({@code EvacModule.onReceiverCall}).</p>
 */
public record ReceiverMenuOpenPacket(int state) implements S2CPacket {

    public ReceiverMenuOpenPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(state);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onReceiverMenu(state);
    }
}
