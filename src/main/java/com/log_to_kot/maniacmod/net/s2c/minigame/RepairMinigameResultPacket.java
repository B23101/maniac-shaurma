package com.log_to_kot.maniacmod.net.s2c.minigame;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: міні-гра завершена (успіх чи провал) — клієнт
 * закриває екран міні-гри незалежно від результату. Провал не показує
 * тут ЖОДНОЇ додаткової причини (не встиг/не той колір/не влучив) —
 * гравець і так щойно бачив, що саме сталося на власному екрані;
 * серверу для інших слухачів (наприклад підсвітки генератора)
 * достатньо самого факту "провалено".
 */
public record RepairMinigameResultPacket(boolean success) implements S2CPacket {

    public RepairMinigameResultPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(success);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onRepairMinigameResult(this);
    }
}
