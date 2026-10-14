package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт (лише маньяку): твій удар влучив.
 *
 * Клієнт показує {@code ManiacHitConfirmFx} — маркер влучання на прицілі, червоний
 * відблиск по краях і короткий поштовх камери. Це відповідник {@link ManiacHitPacket}
 * (його бачить жертва), але інший за виглядом: маньяк має чітко зрозуміти, що влучив.
 *
 * @param downed true, якщо удар збив жертву з ніг — ефект сильніший
 */
public record ManiacHitConfirmPacket(boolean downed) implements S2CPacket {

    public ManiacHitConfirmPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(downed);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ManiacHitConfirmFx.trigger(downed);
    }
}
