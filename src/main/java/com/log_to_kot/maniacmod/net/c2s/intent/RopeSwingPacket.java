package com.log_to_kot.maniacmod.net.c2s.intent;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: підвішена жертва мотузки натиснула A (-1) або D (+1).
 *
 * Клієнт нічого не вирішує (чи «в такт», чи вдало) — лише повідомляє
 * напрямок. Сервер сам звіряє з фазою маятника й інтервалом між
 * натисканнями ({@code TrapModule#onRopeSwing}); якщо гравець не
 * підвішений — пакет мовчки ігнорується.
 */
public record RopeSwingPacket(byte dir) implements ModPacket {

    public RopeSwingPacket(FriendlyByteBuf buf) {
        this(buf.readByte());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(dir);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onRopeSwing(ModNetwork.sender(ctx), dir);
    }
}
