package com.log_to_kot.maniacmod.net.s2c.evac;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: диспетчер виходить в ефір. Несе лише НОМЕР сценарію — сам текст, звукові
 * файли і таймінги живуть на клієнті ({@code ClientDispatcher}), бо озвучка — це ресурс-пак, а не
 * логіка матчу. Сервер вирішує тільки «коли» і «кому».
 *
 * @param script номер сценарію, див. {@link #CALL}
 */
public record DispatcherPacket(int script) implements S2CPacket {

    /** Після виклику вертольота з приймача: «вертоліт летить, дайте мітку». */
    public static final int CALL = 0;

    public DispatcherPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(script);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.evac.ClientDispatcher.start(script);
    }
}
