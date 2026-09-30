package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт (лише жертві): тебе щойно вдарив маньяк.
 *
 * Клієнт за цим пакетом вмикає потужний екранний ефект
 * ({@code ManiacHitEffect}): спалах, червону віньєтку, подряпини, удар
 * камери. Сам пакет нічого не вирішує в матчі — суто відчуття удару.
 *
 * @param downed true, якщо удар збив жертву з ніг — ефект тоді сильніший
 */
public record ManiacHitPacket(boolean downed) implements S2CPacket {

    public ManiacHitPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(downed);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ManiacHitEffect.trigger(downed);
    }
}
