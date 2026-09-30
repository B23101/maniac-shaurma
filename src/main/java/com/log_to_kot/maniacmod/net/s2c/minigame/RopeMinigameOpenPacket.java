package com.log_to_kot.maniacmod.net.s2c.minigame;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт (лише підвішеній жертві): відкрити екран міні-гри
 * розкачування ({@code RopeSwingScreen}). Шлеться у відповідь на F
 * ({@code TrapEscapeStartPacket}), як і міні-гра капкана.
 */
public record RopeMinigameOpenPacket() implements S2CPacket {

    public RopeMinigameOpenPacket(FriendlyByteBuf buf) {
        this();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        // Порожньо: кут і розмах клієнт бере із синхронізованої сутності мотузки.
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onRopeMinigameOpen();
    }
}
