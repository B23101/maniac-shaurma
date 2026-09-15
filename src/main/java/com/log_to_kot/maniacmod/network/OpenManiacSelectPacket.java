package com.log_to_kot.maniacmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → Client (тільки гравцю-маньяку).
 * Відкриває ManiacSelectScreen — меню вибору персонажа.
 */
public class OpenManiacSelectPacket {

    public OpenManiacSelectPacket() {}

    public static OpenManiacSelectPacket decode(FriendlyByteBuf buf) {
        return new OpenManiacSelectPacket();
    }

    public void encode(FriendlyByteBuf buf) { /* порожній пакет */ }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    net.minecraft.client.Minecraft.getInstance().setScreen(
                        new com.log_to_kot.maniacmod.client.screen.ManiacSelectScreen()
                    );
                });
            })
        );
        ctx.setPacketHandled(true);
    }
}
