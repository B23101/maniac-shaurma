package com.log_to_kot.maniacmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Сервер → клієнт: прогрес ремонту генератора.
 *
 * progress  — відсоток поточного етапу (0–100), або -1 щоб сховати бар
 * stage     — поточний етап (1-based)
 * stages    — всього етапів
 */
public class GeneratorProgressPacket {

    public final int progress; // -1 = сховати
    public final int stage;
    public final int stages;

    /** Показати прогрес */
    public GeneratorProgressPacket(int progress, int stage, int stages) {
        this.progress = progress;
        this.stage    = stage;
        this.stages   = stages;
    }

    /** Сховати бар */
    public GeneratorProgressPacket() {
        this(-1, 0, 0);
    }

    public static GeneratorProgressPacket decode(FriendlyByteBuf buf) {
        return new GeneratorProgressPacket(buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static void encode(GeneratorProgressPacket pkt, FriendlyByteBuf buf) {
        buf.writeInt(pkt.progress);
        buf.writeInt(pkt.stage);
        buf.writeInt(pkt.stages);
    }

    public static void handle(GeneratorProgressPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                com.log_to_kot.maniacmod.client.overlay.GeneratorProgressOverlay
                    .setProgress(pkt.progress, pkt.stage, pkt.stages)
            )
        );
        ctx.get().setPacketHandled(true);
    }
}
