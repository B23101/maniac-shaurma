package com.log_to_kot.maniacmod.net.c2s.intent;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: гравець натиснув ПКМ, дивлячись на предмет на землі
 * КРІЗЬ траву (ванільний приціл там «впирається» в траву, тож звичайний
 * пакет взаємодії з сутністю не летить).
 *
 * Несе лише id сутності — намір «взяти оцей». Чи дійсно гравець на неї
 * дивиться, чи в межах дальності, чи фаза дозволяє — сервер перевіряє
 * заново ({@code ServerPacketHandler#onGroundItemPickup}).
 */
public record GroundItemPickupPacket(int entityId) implements ModPacket {

    public GroundItemPickupPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onGroundItemPickup(ModNetwork.sender(ctx), entityId);
    }
}
