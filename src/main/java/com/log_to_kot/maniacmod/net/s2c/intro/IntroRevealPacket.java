package com.log_to_kot.maniacmod.net.s2c.intro;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: гравці на місцях, контроль повернено — можна розтемнювати
 * екран разом із початком гри. Клієнт розтемнює не раніше, ніж його власний
 * екран став повністю чорним і догрузились чанки навколо гравця.
 */
public record IntroRevealPacket() implements S2CPacket {

    public IntroRevealPacket(FriendlyByteBuf buf) {
        this();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {}

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.intro.IntroClient.reveal();
    }
}
