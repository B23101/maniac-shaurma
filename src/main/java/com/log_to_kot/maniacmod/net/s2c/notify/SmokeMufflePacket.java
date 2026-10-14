package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт (лише тому, хто в хмарі димової міни): приглушити всі
 * звуки або повернути звичайну гучність.
 *
 * ── Чому окремий пакет, а не ефект ───────────────────────────────────
 * Ванілі немає ефекту «глухота». Глушіння робить сам клієнт — множником
 * загальної гучності ({@code client.SmokeMuffle}); сервер лише каже,
 * КОЛИ вмикати, і з яким множником (конфіг живе на СЕРВЕРІ — на
 * виділеному сервері клієнт його не має, як і з тряскою дроту в
 * {@link WireShockPacket}). Шлеться рівно двічі за хмару: коли гравець
 * увійшов у першу й коли вийшов з останньої.
 *
 * @param active true — глушити; false — повернути звук
 * @param factor множник загальної гучності, 0..1 (0.15 — звуки тихіші на 85%);
 *               при {@code active=false} не читається
 */
public record SmokeMufflePacket(boolean active, float factor) implements S2CPacket {

    /** Повернути звичайну гучність. */
    public static SmokeMufflePacket stop() {
        return new SmokeMufflePacket(false, 1.0f);
    }

    public SmokeMufflePacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readFloat());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeFloat(factor);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onSmokeMuffle(active, factor);
    }
}
