package com.log_to_kot.maniacmod.net.c2s.minigame;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: гравець перетягнув дріт лівого контакту
 * {@code leftSlot} і відпустив його на правому контакті
 * {@code rightSlot}. Сервер сам перевіряє, чи це правильна пара
 * кольорів ({@code WireMinigameLayout.isCorrectPair}) — клієнт лише
 * повідомляє факт перетягування, не результат.
 */
public record WireMinigameDropPacket(int leftSlot, int rightSlot) implements ModPacket {

    public WireMinigameDropPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(leftSlot);
        buf.writeVarInt(rightSlot);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onWireMinigameDrop(ModNetwork.sender(ctx), leftSlot, rightSlot);
    }
}
