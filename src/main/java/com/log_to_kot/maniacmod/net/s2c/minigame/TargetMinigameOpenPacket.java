package com.log_to_kot.maniacmod.net.s2c.minigame;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: відкрити екран міні-гри "ціль" — повзунок і
 * нерухома ціль. Один пакет на ОБИДВІ такі міні-гри: ремонт генератора
 * (Generator Startup) і саморятунок із капкана (див. {@code TrapModule}).
 * Механіка однакова (та сама формула траєкторії, той самий екран), різні
 * лише значення з конфігу — тож окремий пакет був би копією заради
 * назви. Кому саме належить відкритий екран, клієнт знає зі свого
 * стану (в капкані чи ні), а не з пакета.
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
        BlockPos originPos,
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

    // ── originPos ────────────────────────────────────────────────────────
    // Де міні-гра виникла: генератор або капкан. Клієнт його НЕ читає
    // (екран малює саму механіку, а не місце) — поле лишається як
    // контекст для діагностики й майбутніх розширень (напр. підсвітки
    // джерела міні-гри). Назва свідомо не "generatorPos": капкан теж
    // надсилає сюди свою позицію.

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(originPos);
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
