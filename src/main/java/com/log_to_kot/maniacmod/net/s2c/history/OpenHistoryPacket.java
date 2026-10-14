package com.log_to_kot.maniacmod.net.s2c.history;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Сервер → клієнт: «відкрий екран історії». Шлеться командою {@code /maniac history [гравець]}
 * (вона вже вимагає права оператора). {@code target == null} — архів усіх матчів, інакше — історія
 * вказаного гравця. Права все одно перевіряє сервер при кожному запиті даних ({@code HistoryAccess}),
 * цей пакет лише відкриває екран.
 */
public record OpenHistoryPacket(UUID target, String targetName) implements S2CPacket {

    public OpenHistoryPacket {
        targetName = targetName == null ? "" : targetName;
    }

    public OpenHistoryPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean() ? buf.readUUID() : null, buf.readUtf(64));
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(target != null);
        if (target != null) buf.writeUUID(target);
        buf.writeUtf(targetName, 64);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onOpenHistory(this);
    }
}
