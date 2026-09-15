package com.log_to_kot.maniacmod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → maniac client.
 * Надсилає поточну частку перезарядки (0.0 = готово, 1.0 = повна перезарядка)
 * щоб клієнт міг намалювати overlay на іконці здібності.
 */
public class AbilityCooldownPacket {

    private final float fraction;

    public AbilityCooldownPacket(float fraction) { this.fraction = fraction; }

    public static AbilityCooldownPacket decode(FriendlyByteBuf buf) {
        return new AbilityCooldownPacket(buf.readFloat());
    }

    public void encode(FriendlyByteBuf buf) { buf.writeFloat(fraction); }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                com.log_to_kot.maniacmod.client.overlay.AbilityHudOverlay.setCooldownFraction(fraction)
            )
        );
        ctx.setPacketHandled(true);
    }
}
