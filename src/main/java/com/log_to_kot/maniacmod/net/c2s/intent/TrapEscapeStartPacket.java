package com.log_to_kot.maniacmod.net.c2s.intent;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: жертва капкана натиснула F, щоб спробувати
 * вибратись — тобто погодилась почати саму спробу міні-гри "ціль".
 *
 * ── Навіщо окремий пакет, а не автоматичний старт у {@code trigger()} ──
 * За дизайном у момент захлопування капкана гравець спершу бачить лише
 * текстовий оверлей-підказку ("Натисніть F, щоб вибратися") — без
 * тайм-ауту, без запущеного повзунка. Лише після натискання F сервер
 * запускає {@code TrapEscapeMinigame} (звідси й починається відлік
 * {@code trapEscapeTicks} і сама формула повзунка) і надсилає
 * {@code TargetMinigameOpenPacket}. Без цього пакета сервер довелося б
 * запускати спробу одразу при захлопуванні — і тайм-аут спроби йшов би
 * ще ДО того, як гравець встиг прочитати підказку й натиснути клавішу.
 *
 * Пакет шлеться на кожне натискання F, поки гравець у капкані й ще НЕ
 * витратив свою єдину спробу — повторні натискання після старту
 * спроби сервер просто ігнорує (див. {@code TrapModule#onEscapeStart}).
 */
public record TrapEscapeStartPacket() implements ModPacket {

    public TrapEscapeStartPacket(FriendlyByteBuf buf) {
        this();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        // Порожньо.
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onTrapEscapeStart(ModNetwork.sender(ctx));
    }
}
