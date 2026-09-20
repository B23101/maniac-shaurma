package com.log_to_kot.maniacmod.net.s2c.minigame;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: часткове просування всередині вже відкритої
 * міні-гри — TARGET-влучення зараховано (ще не останнє) або
 * WIRES-дріт під'єднано правильно (ще не всі 4). Успіх/провал усієї
 * міні-гри — окремий {@link RepairMinigameResultPacket}, бо тоді
 * екран ще й закривається.
 *
 * @param hitsSoFar     для TARGET: скільки влучень уже зараховано.
 * @param leftSlot      для WIRES: який лівий контакт щойно правильно
 *                      з'єднано (клієнт малює цей дріт як
 *                      "підтверджений").
 * @param rightSlot     для WIRES: до якого правого контакту.
 */
public record RepairMinigameProgressPacket(int hitsSoFar, int leftSlot, int rightSlot) implements S2CPacket {

    /** TARGET: чергове влучення зараховано. */
    public static RepairMinigameProgressPacket targetHit(int hitsSoFar) {
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
