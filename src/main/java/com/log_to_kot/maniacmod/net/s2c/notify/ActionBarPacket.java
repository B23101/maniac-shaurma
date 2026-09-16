package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: повідомлення в actionbar через
 * shaurma-lib ActionBarMessageSystem.
 *
 * ── Категорія: notify ────────────────────────────────────────────────
 * Одноразова ПОДІЯ в моменті, не стан. На відміну від {@code vitals}
 * чи {@code matchstate}, клієнт нічого не запам'ятовує і не відраховує
 * після отримання — просто показує й забуває. Якщо колись знадобиться
 * "показувати, поки не мине N тіків" — це вже {@code actionprogress},
 * не {@code notify}.
 *
 * Замінює десятки викликів {@code sendSystemMessage("§c...")} з v3:
 * там кожне повідомлення несло кольорові коди прямо в рядку, тому
 * змінити стиль означало правити рядки по всьому моду.
 *
 * @param translationKey ключ локалізації, не готовий текст — інакше
 *                       мова прив'язана до сервера, а не до клієнта
 * @param args           аргументи підстановки
 */
public record ActionBarPacket(ActionBarMessageType type, String translationKey,
                               String[] args) implements S2CPacket {

    public ActionBarPacket(ActionBarMessageType type, String translationKey) {
        this(type, translationKey, new String[0]);
    }

    public ActionBarPacket(FriendlyByteBuf buf) {
        this(buf.readEnum(ActionBarMessageType.class), buf.readUtf(), readArgs(buf));
    }

    private static String[] readArgs(FriendlyByteBuf buf) {
        String[] out = new String[buf.readVarInt()];
        for (int i = 0; i < out.length; i++) out[i] = buf.readUtf();
        return out;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(type);
        buf.writeUtf(translationKey);
        buf.writeVarInt(args.length);
        for (String arg : args) buf.writeUtf(arg);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler
            .onActionBar(type, translationKey, args);
    }
}
