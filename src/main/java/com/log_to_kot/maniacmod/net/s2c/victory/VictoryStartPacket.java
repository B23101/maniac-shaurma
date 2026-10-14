package com.log_to_kot.maniacmod.net.s2c.victory;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Початок сцени перемоги. Усі тривалості — мс від моменту прийому пакета:
 * затемнення → чорний екран, поки вантажаться чанки → камера їде → титул → затемнення.
 *
 * @param survivorsWin {@code true} — сцена перемоги ГРАВЦІВ (зелений титул «ГРАВЦІ ПЕРЕМОГЛИ»),
 *                     {@code false} — перемоги маніяка (червоний титул)
 */
public record VictoryStartPacket(int fadeInMs, int loadMs, int moveMs, int titleMs, int fadeOutMs,
                                 boolean survivorsWin)
    implements S2CPacket {

    public VictoryStartPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
            buf.readBoolean());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(fadeInMs);
        buf.writeVarInt(loadMs);
        buf.writeVarInt(moveMs);
        buf.writeVarInt(titleMs);
        buf.writeVarInt(fadeOutMs);
        buf.writeBoolean(survivorsWin);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.victory.VictoryClient.start(this);
    }
}
