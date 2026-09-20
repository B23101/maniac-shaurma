package com.log_to_kot.maniacmod.net.s2c.minigame;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: відкрити екран міні-гри "ціль" (Generator Startup).
 *
 * Клієнт рахує рух повзунка ЛОКАЛЬНО від {@code seed} — сервер не
 * веде позицію щотік (див. {@code TargetMinigameSpec} докстрінг).
 * {@code hitsRequired} прийшло тут, а не читається з клієнтського
 * конфігу, щоб адмінська зміна конфігу під час матчу не розсинхронила
 * "скільки влучень треба" між сервером і вже відкритим екраном.
 *
 * Екран НЕ закривається по ESC (блокується клієнтським кодом) — вихід
 * інакше (дисконект, надмірна відстань до генератора) сервер трактує
 * як провал і надсилає {@link RepairMinigameResultPacket} сам, без
 * додаткового пакета від клієнта.
 */
public record TargetMinigameOpenPacket(
        BlockPos generatorPos,
        long seed,
        double cursorSpeed,
        double hitZoneWidth,
        double targetPosition,
        int hitsRequired
) implements S2CPacket {

    public TargetMinigameOpenPacket(FriendlyByteBuf buf) {
        this(buf.readBlockPos(), buf.readLong(), buf.readDouble(),
             buf.readDouble(), buf.readDouble(), buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(generatorPos);
        buf.writeLong(seed);
        buf.writeDouble(cursorSpeed);
        buf.writeDouble(hitZoneWidth);
        buf.writeDouble(targetPosition);
        buf.writeVarInt(hitsRequired);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onTargetMinigameOpen(this);
    }
}
