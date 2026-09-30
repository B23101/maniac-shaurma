package com.log_to_kot.maniacmod.net.s2c.minigame;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: результат ОДНІЄЇ спроби в міні-грі «ціль» — разом
 * із НОВОЮ позицією цілі. Шле її і капкан (після кожної спроби), і
 * ремонт генератора (після кожного ВЛУЧАННЯ; промах там — провал і
 * закриття гри, тож нової позиції не потрібно).
 *
 * ── Навіщо окремий пакет, а не {@code RepairMinigameProgressPacket} ──
 * {@code RepairMinigameProgressPacket} несе лише лічильник влучень
 * (і потрібен дротам). Ціль «цілі» ж ПЕРЕМІЩУЄТЬСЯ (у капкані після
 * КОЖНОЇ спроби, і влучання, і промаху; у генераторі після влучання), а
 * самого вердикту «влучив/схибив» клієнту треба для окремої анімації та
 * звуку. Тому це окремий пакет, а не розширення спільного: розширення
 * змусило б дроти возити поля, які вони ніколи не заповнюють.
 *
 * ── Хто вирішує ──────────────────────────────────────────────────────
 * Вердикт — ТУТ, від сервера. Клієнт лише показує те, що прийшло: жодного
 * власного «я, здається, влучив». Це той самий принцип, що й
 * {@code TargetMinigameSpec} докує: позицію повзунка перевіряє сервер.
 *
 * @param hitsSoFar       лічильник влучень ПІСЛЯ цієї спроби (у капкані
 *                        може й зменшитись — штраф за промах)
 * @param hit             true — ця спроба була влучанням (зелена
 *                        анімація + звук попадання); false — промах
 *                        (червона анімація + звук промаху)
 * @param targetPosition  НОВА позиція цілі на смузі (0.0-1.0); ціль
 *                        переміщується після кожної спроби, тож уже
 *                        наступний погляд гравця бачить її в іншому місці
 */
public record TrapEscapeAttemptPacket(int hitsSoFar, boolean hit, double targetPosition)
        implements S2CPacket {

    public TrapEscapeAttemptPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readBoolean(), buf.readDouble());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(hitsSoFar);
        buf.writeBoolean(hit);
        buf.writeDouble(targetPosition);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onTrapEscapeAttempt(this);
    }
}
