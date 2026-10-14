package com.log_to_kot.maniacmod.net.s2c.barricade;

import com.log_to_kot.maniacmod.barricade.BoardSpec;
import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → клієнт: відкрити міні-гру «витягни цвяхи» на барикаді (ПКМ ломом).
 *
 * <p>Несе все, чого клієнту досить, щоб намалювати барикаду САМОМУ (дошки, розміри, повороти,
 * текстури) і продовжити з того місця, де зупинилися: які цвяхи вже витягнуті ({@code removed}) і
 * яким дещо розхитаним ({@code looseNails}/{@code looseProgress}). Сервер лишається джерелом
 * правди — клієнт лише показує й шле «тримаю цвях №N» ({@code BarricadeNailHoldPacket}).
 *
 * <p>Повторний пакет для ТОГО Ж {@code key} оновлює вже відкритий екран, а не відкриває новий.
 *
 * @param fixed        фіксована барикада (дошки беруться з моделей блока), а не гнучка
 * @param key          ідентифікатор барикади (для відрізнення «той самий екран / інший»)
 * @param boards       дошки гнучкої барикади (для фіксованої — порожньо)
 * @param lookX,lookZ  горизонтальний напрямок погляду гравця (орієнтація «верху» для дощок на підлозі)
 * @param nailTicks    скільки тіків (за швидкості ×1) утримувати цвях, щоб витягти
 * @param removed      бітова маска вже витягнутих цвяхів ({@link java.util.BitSet#toLongArray()})
 * @param looseNails   індекси цвяхів із ненульовим прогресом
 * @param looseProgress прогрес (тіки) для {@code looseNails}
 */
public record BarricadeGameOpenPacket(boolean fixed, String key, List<BoardSpec> boards,
                                      float lookX, float lookZ, int nailTicks,
                                      long[] removed, int[] looseNails, int[] looseProgress) implements S2CPacket {

    public BarricadeGameOpenPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readUtf(128), readBoards(buf), buf.readFloat(), buf.readFloat(),
            buf.readVarInt(), buf.readLongArray(), buf.readVarIntArray(), buf.readVarIntArray());
    }

    private static List<BoardSpec> readBoards(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<BoardSpec> boards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) boards.add(BoardSpec.read(buf));
        return boards;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(fixed);
        buf.writeUtf(key, 128);
        buf.writeVarInt(boards.size());
        for (BoardSpec board : boards) board.write(buf);
        buf.writeFloat(lookX);
        buf.writeFloat(lookZ);
        buf.writeVarInt(nailTicks);
        buf.writeLongArray(removed);
        buf.writeVarIntArray(looseNails);
        buf.writeVarIntArray(looseProgress);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.barricade.BarricadeGameClient.onOpen(this);
    }
}
