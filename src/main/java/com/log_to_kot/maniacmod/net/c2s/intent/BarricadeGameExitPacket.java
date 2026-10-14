package com.log_to_kot.maniacmod.net.c2s.intent;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/** Клієнт → сервер: гравець закрив екран міні-гри барикади (ESC). Прогрес барикади лишається. */
public record BarricadeGameExitPacket() implements ModPacket {

    public BarricadeGameExitPacket(FriendlyByteBuf buf) {
        this();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {}

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onBarricadeGameExit(ModNetwork.sender(ctx));
    }
}
