package com.log_to_kot.maniacmod.net.c2s.intent;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: виживий натиснув підсвітку генераторів (клавіша 5).
 *
 * ── Категорія: intent ────────────────────────────────────────────────
 * Порожній пакет-намір — уся інформація в самому факті натискання.
 * Сервер перевіряє роль, фазу й кулдаун (30 с) і відповідає
 * {@code GeneratorHighlightPacket}-ом (категорія s2c/actionprogress)
 * або мовчить.
 */
public record HighlightTogglePacket() implements ModPacket {

    public HighlightTogglePacket(FriendlyByteBuf buf) {
        this();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        // Порожньо: пакет без полів.
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onHighlightRequest(ModNetwork.sender(ctx));
    }
}
