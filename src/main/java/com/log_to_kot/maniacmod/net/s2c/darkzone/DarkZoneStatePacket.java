package com.log_to_kot.maniacmod.net.s2c.darkzone;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: "ти зараз (не) в темній зоні". Сервер вирішує
 * {@code active} самостійним тіком ({@code DarkZoneModule.tick}), який
 * перевіряє {@code DarkZonePolygon.contains(player)} для кожного
 * виживого — клієнт нічого не рахує сам, лише плавно переходить
 * intensity локально (див. {@code DarkZoneEffect}).
 * <p>
 * {@code intensity}/{@code transitionTicks} їдуть у пакеті, а не
 * читаються з клієнтського конфігу, — адмінське {@code /maniac reload}
 * має діяти одразу для всіх, хто вже стоїть у зоні, без релогіну.
 */
public record DarkZoneStatePacket(boolean active, float intensity, int transitionTicks)
    implements S2CPacket {

    public DarkZoneStatePacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readFloat(), buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeFloat(intensity);
        buf.writeVarInt(transitionTicks);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler
            .onDarkZoneState(active, intensity, transitionTicks);
    }
}
