package com.log_to_kot.maniacmod.net.s2c.intro;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: почався кіно-вступ. Сам Replay-сценарій іде окремим пакетом
 * бібліотеки ({@code ReplayPlayPacket}); цей несе лише «подачу»: скільки триває
 * вікно Replay, як часто міняти поради, і чи це маньяк.
 */
public record IntroStartPacket(int windowMs, int tipIntervalMs, boolean maniac, boolean hasReplay)
    implements S2CPacket {

    public IntroStartPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(windowMs);
        buf.writeVarInt(tipIntervalMs);
        buf.writeBoolean(maniac);
        buf.writeBoolean(hasReplay);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.intro.IntroClient.start(windowMs, tipIntervalMs, maniac);
    }
}
