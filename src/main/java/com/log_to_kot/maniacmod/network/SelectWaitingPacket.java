package com.log_to_kot.maniacmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → всі клієнти (крім маньяка).
 *
 * Показує / оновлює / прибирає темний overlay з текстом
 * "Маньяк вибирає персонажа" та таймером.
 *
 * active = true  → показати overlay, secondsLeft = поточний відлік
 * active = false → прибрати overlay
 */
public class SelectWaitingPacket {

    private final boolean active;
    private final int     secondsLeft;

    public SelectWaitingPacket(boolean active, int secondsLeft) {
        this.active      = active;
        this.secondsLeft = secondsLeft;
    }

    public static SelectWaitingPacket decode(FriendlyByteBuf buf) {
        return new SelectWaitingPacket(buf.readBoolean(), buf.readVarInt());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeVarInt(secondsLeft);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                com.log_to_kot.maniacmod.client.overlay.SelectWaitingOverlay.setState(active, secondsLeft)
            )
        );
        ctx.setPacketHandled(true);
    }
}
