package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: щойно завершено генератор (обидві стадії пройдено).
 * Надсилається УСІМ виживим одночасно (не лише тому, хто заливав
 * останню каністру) — той самий принцип, що {@code CountdownPacket}:
 * одноразова подія, клієнт нічого сам не рахує.
 *
 * ── Чому index/total, а не сам текст ────────────────────────────────────
 * Числа рахуються на сервері ({@code GeneratorModule.onGeneratorCompleted}
 * — позиція завершеного генератора в {@code MatchOrchestrator.generators()}
 * і {@code GENERATORS_REQUIRED}), а не форматуються заздалегідь у
 * рядок: клієнт сам підставляє їх у локалізований
 * {@code maniacmod.hud.generator.completed_toast} через
 * {@link net.minecraft.network.chat.Component#translatable} — так само,
 * як {@code GeneratorProgressPacket} шле сирі числа, а не готовий текст.
 *
 * @param index номер щойно завершеного генератора (1-based, за
 *              порядком у списку генераторів матчу)
 * @param total скільки генераторів вимагається для відкриття виходу
 *              (GENERATORS_REQUIRED) — саме ця цифра, а не загальна
 *              кількість генераторів на карті (з бонусними за маньяків
 *              їх може бути й більше, але гравцю потрібне число з
 *              задачі, не з розмітки карти)
 */
public record GeneratorCompletedPacket(int index, int total) implements S2CPacket {

    public GeneratorCompletedPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(index);
        buf.writeVarInt(total);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onGeneratorCompleted(index, total);
    }
}
