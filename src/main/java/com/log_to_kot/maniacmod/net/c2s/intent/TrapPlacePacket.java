package com.log_to_kot.maniacmod.net.c2s.intent;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: маньяк натиснув клавішу пастки (Z, X або C).
 *
 * ── Категорія: intent ────────────────────────────────────────────────
 * Одноразовий намір, той самий патерн, що {@link AbilityActivatePacket}.
 *
 * Позиція не передається: її рахує сервер по погляду гравця. Інакше
 * модифікований клієнт ставив би пастки через півкарти.
 *
 * @param slot 0, 1 або 2 — клавіші Z, X, C
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
