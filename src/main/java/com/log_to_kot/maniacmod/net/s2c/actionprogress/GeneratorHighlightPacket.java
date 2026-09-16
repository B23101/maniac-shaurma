package com.log_to_kot.maniacmod.net.s2c.actionprogress;

import com.log_to_kot.maniacmod.map.zones.GeneratorPoi;
import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → клієнт: підсвітка генераторів (клавіша 5 у виживого).
 *
 * ── Категорія: actionprogress ────────────────────────────────────────
 * Результат одноразової дії ("я натиснув 5") з проміжком дії, що
 * клієнт відраховує сам — та сама форма, що й
 * {@link GeneratorProgressPacket} і {@link AbilityCooldownPacket}:
 * сервер каже "ось стан і скільки це триває", клієнт більше нічого
 * не питає, поки не спливе час.
 *
 * Надсилається ОДИН раз на активацію зі списком усіх генераторів та
 * їхніми станами; клієнт сам гасить підсвітку через durationTicks.
 * Це навмисно: тримати підсвітку щотіковим потоком пакетів на 300×300
 * карті з десятком генераторів — марний трафік.
 *
 * Колір рахує сервер (GeneratorPoi.VisualState), щоб клієнт не міг
 * показати стан, якого насправді немає.
 *
 * @param durationTicks скільки тіків тримати підсвітку
 * @param entries       генератори та їхні стани
 */
public record GeneratorHighlightPacket(int durationTicks, List<Entry> entries) implements S2CPacket {

    public record Entry(BlockPos pos, GeneratorPoi.VisualState state) {}

    public GeneratorHighlightPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), readEntries(buf));
    }

    private static List<Entry> readEntries(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new Entry(buf.readBlockPos(), buf.readEnum(GeneratorPoi.VisualState.class)));
        }
        return list;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(durationTicks);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeBlockPos(e.pos());
            buf.writeEnum(e.state());
        }
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler
            .onGeneratorHighlight(durationTicks, entries);
    }
}
