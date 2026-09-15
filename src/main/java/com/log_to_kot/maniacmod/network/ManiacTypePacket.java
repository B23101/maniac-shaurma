package com.log_to_kot.maniacmod.network;

import com.log_to_kot.maniacmod.client.ManiacClientState;
import com.log_to_kot.maniacmod.entity.ManiacType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → Client: assigns or clears the local player's ManiacType.
 * Triggers Mixin overrides for hitbox height/width and camera eye height.
 */
public class ManiacTypePacket {

    /** null = clear (player is no longer maniac) */
    private final ManiacType type;

    public ManiacTypePacket(ManiacType type) { this.type = type; }

    public static ManiacTypePacket decode(FriendlyByteBuf buf) {
        boolean hasType = buf.readBoolean();
        ManiacType type = hasType ? buf.readEnum(ManiacType.class) : null;
        return new ManiacTypePacket(type);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(type != null);
        if (type != null) buf.writeEnum(type);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                if (type != null) {
                    ManiacClientState.setLocalManiac(type);
                } else {
                    ManiacClientState.clearLocalManiac();
                }
            })
        );
        ctx.setPacketHandled(true);
    }
}
