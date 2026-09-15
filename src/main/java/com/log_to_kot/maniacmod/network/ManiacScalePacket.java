package com.log_to_kot.maniacmod.network;

import com.log_to_kot.maniacmod.entity.ManiacEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ManiacScalePacket {

    private final ManiacEntity.ManiacScale scale;

    public ManiacScalePacket(ManiacEntity.ManiacScale scale) { this.scale = scale; }

    public static ManiacScalePacket decode(FriendlyByteBuf buf) {
        return new ManiacScalePacket(buf.readEnum(ManiacEntity.ManiacScale.class));
    }

    public void encode(FriendlyByteBuf buf) { buf.writeEnum(scale); }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        // Scale is purely visual — the renderer reads it from ManiacType via ManiacClientState.
        // No client-side state needs updating here.
        ctxSupplier.get().setPacketHandled(true);
    }
}
