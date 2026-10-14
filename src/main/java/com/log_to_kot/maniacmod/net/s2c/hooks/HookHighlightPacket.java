package com.log_to_kot.maniacmod.net.s2c.hooks;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → ОДНОМУ клієнту (маньяку, що несе жертву): які гаки зараз вільні.
 *
 * <h3>Чому лише йому</h3>
 * За дизайном підсвітка вільних гаків — підказка того, хто несе, а не загальна інформація:
 * виживі не мають бачити, куди маньяк може піти. Тому пакет адресний, а контур малює
 * клієнтський шар {@code ClientEntityGlow} (лише на цьому клієнті), а не серверний
 * {@code setGlowingTag}, який бачили б усі.
 *
 * <h3>Чому не лише id сутностей</h3>
 * Контур можна намалювати лише для сутності, яку клієнт уже має. Сервер ж відправляє сутності
 * в межах {@code view-distance} сервера, тож гак за 400 блоків на сервері з малим радіусом
 * видимості клієнт просто не знає. Тому в пакеті ще й позиції: за ними клієнт малює екранну
 * мітку {@code HookMarker} на будь-якій відстані, а контур додається, коли сутність таки в зоні.
 *
 * Порожній список — «погасити все» (маньяк кинув/повісив жертву).
 */
public record HookHighlightPacket(List<Entry> hooks) implements S2CPacket {

    /** @param entityId id сутності гака на цьому світі (однаковий на сервері й клієнті) */
    public record Entry(int entityId, double x, double y, double z) {}

    public HookHighlightPacket(FriendlyByteBuf buf) {
        this(read(buf));
    }

    private static List<Entry> read(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new Entry(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }
        return list;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(hooks.size());
        for (Entry e : hooks) {
            buf.writeVarInt(e.entityId());
            buf.writeDouble(e.x());
            buf.writeDouble(e.y());
            buf.writeDouble(e.z());
        }
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.hooks.ClientHooks.onHighlight(hooks);
    }
}
