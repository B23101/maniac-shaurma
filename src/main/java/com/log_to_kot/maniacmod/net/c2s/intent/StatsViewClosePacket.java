package com.log_to_kot.maniacmod.net.c2s.intent;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: гравець закрив екран підсумків. Сервер знімає freecam-камеру статистики й
 * повертає гравця в лобі ({@code StatsCameraModule#release}). Без активної камери — нічого не робить.
 */
public record StatsViewClosePacket() implements ModPacket {

    public StatsViewClosePacket(FriendlyByteBuf buf) {
        this();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {}

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ModNetwork.sender(ctx);
        MatchOrchestrator match = ManiacMod.match();
        if (player != null && match != null) match.statsCamera().release(player);
    }
}
