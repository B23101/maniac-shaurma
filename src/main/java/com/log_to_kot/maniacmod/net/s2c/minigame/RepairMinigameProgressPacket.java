package com.log_to_kot.maniacmod.net.s2c.minigame;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: часткове просування всередині вже відкритої
 * міні-гри — поточний лічильник влучень (міні-гра «ціль», чи то ремонт
 * генератора, чи визволення з капкана) або WIRES-дріт під'єднано
 * правильно (ще не всі 4). Успіх/провал усієї міні-гри — окремий
 * {@link RepairMinigameResultPacket}, бо тоді екран ще й закривається.
 *
 * ── Чому «лічильник», а не «влучання» ────────────────────────────────
 * Капкан карає за промах не провалом, а ЗНЯТТЯМ влучень
 * (див. {@code TrapEscapeMinigame}), тож те саме поле несе і більше, і
 * менше число. Клієнту цього досить: він просто показує те, що прислав
 * сервер, і розморожує повзунок для наступної спроби (див.
 * {@code TargetMinigameScreen#onHit}) — окремий «пакет промаху» не дав
 * би нічого, крім ще одного поля в протоколі.
 *
 * @param hitsSoFar     для TARGET: скільки влучень зараз зараховано.
 * @param leftSlot      для WIRES: який лівий контакт щойно правильно
 *                      з'єднано (клієнт малює цей дріт як
 *                      "підтверджений").
 * @param rightSlot     для WIRES: до якого правого контакту.
 */
public record RepairMinigameProgressPacket(int hitsSoFar, int leftSlot, int rightSlot) implements S2CPacket {

    /**
     * TARGET: стан лічильника після спроби — влучання (число більшає)
     * або промах зі штрафом у капкані (число меншає).
     */
    public static RepairMinigameProgressPacket attempt(int hitsSoFar) {
        return new RepairMinigameProgressPacket(hitsSoFar, -1, -1);
    }

    /** WIRES: дріт правильно під'єднано. */
    public static RepairMinigameProgressPacket wireConnected(int leftSlot, int rightSlot) {
        return new RepairMinigameProgressPacket(-1, leftSlot, rightSlot);
    }

    public RepairMinigameProgressPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(hitsSoFar);
        buf.writeVarInt(leftSlot);
        buf.writeVarInt(rightSlot);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onRepairMinigameProgress(this);
    }
}
