package com.log_to_kot.maniacmod.net.s2c.victory;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/** Сцена скінчилась, гравця вже телепортовано: клієнт розтемнює екран. */
public record VictoryEndPacket() implements S2CPacket {

    public VictoryEndPacket(FriendlyByteBuf buf) {
        this();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {}

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.victory.VictoryClient.end();
    }
}
