package com.log_to_kot.maniacmod.net.c2s.intent;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: гравець тисне пробіл, щоб підвестися після падіння.
 *
 * ── Категорія: intent ────────────────────────────────────────────────
 * За дизайном падіння з висоти збиває з ніг, і встати можна лише
 * натисканням пробілу. Пакет шлеться на КОЖНЕ натискання, поки гравець
 * лежить — сервер рахує потрібну кількість і сам вирішує, коли
 * гравець підвівся (і чи зламалась нога). Технічно це серія
 * одноразових намірів, не утримання (див. {@code hold}) — клієнт не
 * повідомляє "тримаю", лише "тисну ще раз".
 *
 * Спам захищений на сервері: поза станом «збитий з ніг» пакет просто
 * ігнорується.
 */
public record StandUpPacket() implements ModPacket {

    public StandUpPacket(FriendlyByteBuf buf) {
        this();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        // Порожньо.
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onStandUpAttempt(ModNetwork.sender(ctx));
    }
}
