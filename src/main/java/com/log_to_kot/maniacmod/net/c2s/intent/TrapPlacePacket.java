package com.log_to_kot.maniacmod.net.c2s.intent;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: маньяк підтвердив розміщення пастки (ПКМ у режимі
 * розміщення, який відкриває клавіша 5, 6 або 7).
 *
 * ── Категорія: intent ────────────────────────────────────────────────
 * Одноразовий намір, той самий патерн, що {@link AbilityActivatePacket}.
 *
 * Позиція не передається: її рахує сервер по погляду гравця. Інакше
 * модифікований клієнт ставив би пастки через півкарти. Сам режим
 * розміщення (зелений/червоний квадрат, Esc) — суто клієнтський UI:
 * серверу про нього знати не потрібно, він отримує лише підтвердження.
 *
 * @param slot 0, 1 або 2 — клавіші 5, 6, 7
 */
public record TrapPlacePacket(int slot) implements ModPacket {

    public TrapPlacePacket(FriendlyByteBuf buf) {
        this(buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(slot);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onTrapPlace(ModNetwork.sender(ctx), slot);
    }
}
