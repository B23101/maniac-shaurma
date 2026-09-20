package com.log_to_kot.maniacmod.net.c2s.minigame;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: гравець клікнув у міні-грі "ціль".
 *
 * {@code cursorPosition} — де, на думку клієнта, зараз стоїть повзунок
 * (0.0-1.0), рахований локально від seed'а, який прислав
 * {@code TargetMinigameOpenPacket}. Сервер САМ перевіряє влучення
 * (проти {@code targetPosition}/{@code hitZoneWidth} з того самого
 * запуску) — це координата-твердження від клієнта, а не готовий
 * вердикт "влучив я чи ні".
 */
public record TargetMinigameClickPacket(double cursorPosition) implements ModPacket {

    public TargetMinigameClickPacket(FriendlyByteBuf buf) {
        this(buf.readDouble());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeDouble(cursorPosition);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onTargetMinigameClick(ModNetwork.sender(ctx), cursorPosition);
    }
}
