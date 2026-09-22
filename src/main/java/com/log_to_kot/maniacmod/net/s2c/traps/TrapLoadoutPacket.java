package com.log_to_kot.maniacmod.net.s2c.traps;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → клієнт: які пастки маньяк узяв у цей матч і з якою дальністю
 * їх можна ставити.
 *
 * ── Навіщо ───────────────────────────────────────────────────────────
 * Клієнт сам не знає ні набору пасток (вибір робиться в меню й
 * перевіряється сервером), ні серверних налаштувань. Панель зліва,
 * режим розміщення й квадрат-підказка живуть із цього пакета.
 *
 * ── Що НЕ передається ────────────────────────────────────────────────
 * Ні позицій виживих, ні мінімальної відстані до них. Клієнт не має
 * знати, де виживі, навіть побічно (через колір квадрата): відмову
 * «надто близько до гравця» маньяк отримує лише від сервера після
 * підтвердження.
 *
 * ── Формат ───────────────────────────────────────────────────────────
 * Порядок {@code trapIds} = порядок клавіш 5, 6, 7. Порожній список —
 * пасток немає (наприклад, скидання матчу): клієнт ховає панель.
 *
 * ── Назви й іконки ───────────────────────────────────────────────────
 * Не в пакеті: клієнт будує ключ перекладу з id за конвенцією
 * {@code maniacmod.trap.name.<id>}, а іконку — з
 * {@code textures/gui/traps/<id>.png}. Так пакет лишається компактним,
 * а нова пастка не вимагає змін у форматі.
 *
 * @param trapIds    id обраних пасток у порядку слотів (0..2)
 * @param placeRange дальність розміщення в блоках
 */
public record TrapLoadoutPacket(List<String> trapIds, double placeRange) implements S2CPacket {

    /** Немає пасток — клієнт ховає панель і виходить із режиму розміщення. */
    public static TrapLoadoutPacket empty() {
        return new TrapLoadoutPacket(List.of(), 0.0);
    }

    public TrapLoadoutPacket(FriendlyByteBuf buf) {
        this(readIds(buf), buf.readDouble());
    }

    private static List<String> readIds(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) ids.add(buf.readUtf());
        return ids;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(trapIds.size());
        for (String id : trapIds) buf.writeUtf(id);
        buf.writeDouble(placeRange);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onTrapLoadout(trapIds, placeRange);
    }
}
