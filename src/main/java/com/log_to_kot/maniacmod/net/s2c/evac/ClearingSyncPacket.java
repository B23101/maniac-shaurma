package com.log_to_kot.maniacmod.net.s2c.evac;

import com.log_to_kot.maniacmod.map.zones.ClearingZone;
import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → виживі: прямокутники галявин. Клієнт малює їх жовтим контуром на землі, лише коли
 * в руці сигнальна ракета (як зелений/червоний квадрат пастки в маньяка).
 *
 * <p>Шлеться на старті полювання й при поверненні гравця в матч. Висоти немає навмисно: зона —
 * стовпець по XZ, а клієнт сам кладе малюнок на рельєф.</p>
 */
public record ClearingSyncPacket(List<Rect> rects) implements S2CPacket {

    private static final int MAX = 64;

    public record Rect(int minX, int minZ, int maxX, int maxZ) {}

    public ClearingSyncPacket {
        rects = List.copyOf(rects);
    }

    public static ClearingSyncPacket of(List<ClearingZone> zones) {
        List<Rect> list = new ArrayList<>();
        for (ClearingZone z : zones) {
            if (z.hasBothCorners()) list.add(new Rect(z.minX(), z.minZ(), z.maxX(), z.maxZ()));
        }
        return new ClearingSyncPacket(list);
    }

    public ClearingSyncPacket(FriendlyByteBuf buf) {
        this(read(buf));
    }

    private static List<Rect> read(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX);
        List<Rect> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Rect(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()));
        }
        return list;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        int n = Math.min(rects.size(), MAX);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Rect r = rects.get(i);
            buf.writeInt(r.minX());
            buf.writeInt(r.minZ());
            buf.writeInt(r.maxX());
            buf.writeInt(r.maxZ());
        }
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onClearings(rects);
    }
}
