package com.log_to_kot.maniacmod.network;

import com.log_to_kot.maniacmod.entity.ManiacType;
import com.log_to_kot.maniacmod.game.ManiacGameManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → Server.
 * Гравець вибрав тип маньяка в меню — надсилає вибір на сервер.
 */
public class ManiacSelectResponsePacket {

    private final ManiacType chosen;

    public ManiacSelectResponsePacket(ManiacType chosen) {
        this.chosen = chosen;
    }

    public static ManiacSelectResponsePacket decode(FriendlyByteBuf buf) {
        return new ManiacSelectResponsePacket(buf.readEnum(ManiacType.class));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(chosen);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;
            // Передаємо вибір до PendingGameStarter
            com.log_to_kot.maniacmod.game.PendingGameStarter.onManiacTypeSelected(sender, chosen);
        });
        ctx.setPacketHandled(true);
    }
}
