package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт (лише жертві): показати чи сховати текстову підказку
 * "Натисни F, щоб вибратись".
 *
 * ── Чому окремий пакет, а не виведення з isTrapped() ─────────────────
 * {@code isTrapped()} лишається {@code true} увесь час, поки жертва в
 * капкані, — і ДО натискання F, і ПІСЛЯ втраченої спроби (час на F
 * вийшов або міні-гра провалена). Лише сервер знає, чи підказка ще
 * має сенс, тому він явно каже клієнту "показуй" / "ховай", а клієнт
 * не здогадується сам (той самий принцип, що {@link ManiacStunPacket}).
 *
 * @param active true — підказка активна (F ще щось робить); false —
 *               сховати (F натиснуто, час вийшов, звільнено тощо)
 */
public record TrapEscapePromptPacket(boolean active) implements S2CPacket {

    public TrapEscapePromptPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onTrapEscapePrompt(active);
    }
}
