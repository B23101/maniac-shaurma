package com.log_to_kot.maniacmod.net.s2c.barricade;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: міні-гру барикади завершено на боці сервера.
 *
 * @param reason {@link #DONE} — барикаду знято (екран покаже фінал і закриється сам);
 *               решта — екран закривається одразу ({@link #FAR}: відійшов, {@link #CROWBAR}: лом
 *               зник/зламався, {@link #STATE}: фаза/стан гравця вже не дозволяє)
 */
public record BarricadeGameClosePacket(byte reason) implements S2CPacket {

    public static final byte DONE = 0;
    public static final byte FAR = 1;
    public static final byte CROWBAR = 2;
    public static final byte STATE = 3;

    public BarricadeGameClosePacket(FriendlyByteBuf buf) {
        this(buf.readByte());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(reason);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.barricade.BarricadeGameClient.onClose(reason);
    }
}
