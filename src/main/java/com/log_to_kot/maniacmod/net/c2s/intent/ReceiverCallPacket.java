package com.log_to_kot.maniacmod.net.c2s.intent;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: натиснуто «Викликати вертоліт» в меню приймача. Без полів — це намір
 * («хочу викликати»), а все решта (фаза, роль, стан, відстань до приймача) сервер перевіряє сам.
 */
public record ReceiverCallPacket() implements ModPacket {

    public ReceiverCallPacket(FriendlyByteBuf buf) {
        this();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {}

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onReceiverCall(ModNetwork.sender(ctx));
    }
}
