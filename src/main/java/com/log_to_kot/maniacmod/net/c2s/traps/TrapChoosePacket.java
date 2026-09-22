package com.log_to_kot.maniacmod.net.c2s.traps;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Клієнт → сервер: маньяк обрав пастки в меню на початку матчу.
 *
 * ── Категорія ────────────────────────────────────────────────────────
 * Одноразовий намір, як {@code ManiacSelectPacket}. Сервер приймає лише
 * реальні індекси зі списку пасток архетипу, без дублів і не більше
 * {@code trapsPerMatch} (див. {@code TrapModule#onTrapsChosen}) —
 * модифікований клієнт не може взяти пастку, якої в маньяка немає.
 *
 * @param indices індекси в {@code ManiacArchetype#traps()}; порядок = порядок клавіш 5, 6, 7
 */
public record TrapChoosePacket(List<Integer> indices) implements ModPacket {

    /** Верхня межа довжини списку при читанні: захист від пакета-бомби. */
    private static final int MAX_READ = 8;

    public TrapChoosePacket(FriendlyByteBuf buf) {
        this(readIndices(buf));
    }

    private static List<Integer> readIndices(FriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), MAX_READ);
        List<Integer> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) out.add(buf.readVarInt());
        return out;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(indices.size());
        for (int index : indices) buf.writeVarInt(index);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onTrapsChosen(ModNetwork.sender(ctx), indices);
    }
}
