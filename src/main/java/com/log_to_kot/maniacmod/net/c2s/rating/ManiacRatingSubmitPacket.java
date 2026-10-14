package com.log_to_kot.maniacmod.net.c2s.rating;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.net.s2c.rating.ManiacRatingSyncPacket;
import com.log_to_kot.maniacmod.server.rating.ManiacRatingService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Клієнт → сервер: гравець надсилає оцінки маньяків і коментар.
 *
 * ── Категорія: rating ────────────────────────────────────────────────
 * Не {@code intent} (це не дія в грі) і не {@code selection} (вибір
 * одного варіанту): це ФОРМА з багатьма полями, яку гравець заповнює й
 * підтверджує. Сервер не довіряє нічому з неї — округлення до 0.5,
 * межі 0..5, відсів невідомих id і обрізання коментаря робить
 * {@link ManiacRatingService#onSubmit}.
 */
public record ManiacRatingSubmitPacket(List<ManiacRatingSyncPacket.Entry> entries,
                                       String comment) implements ModPacket {

    private static final int MAX_ENTRIES = 128;

    public ManiacRatingSubmitPacket {
        entries = List.copyOf(entries);
        comment = comment == null ? "" : comment;
    }

    public ManiacRatingSubmitPacket(FriendlyByteBuf buf) {
        this(readEntries(buf), buf.readUtf(ManiacRatingSyncPacket.MAX_COMMENT));
    }

    private static List<ManiacRatingSyncPacket.Entry> readEntries(FriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<ManiacRatingSyncPacket.Entry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new ManiacRatingSyncPacket.Entry(
                buf.readUtf(64), buf.readFloat(), buf.readFloat(), buf.readFloat()));
        }
        return list;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        int size = Math.min(entries.size(), MAX_ENTRIES);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            ManiacRatingSyncPacket.Entry e = entries.get(i);
            buf.writeUtf(e.id(), 64);
            buf.writeFloat(e.look());
            buf.writeFloat(e.abilities());
            buf.writeFloat(e.fear());
        }
        buf.writeUtf(comment, ManiacRatingSyncPacket.MAX_COMMENT);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ManiacRatingService.onSubmit(ModNetwork.sender(ctx), entries, comment);
    }
}
