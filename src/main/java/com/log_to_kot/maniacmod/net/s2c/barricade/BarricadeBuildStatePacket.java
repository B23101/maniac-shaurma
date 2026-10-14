package com.log_to_kot.maniacmod.net.s2c.barricade;

import com.log_to_kot.maniacmod.barricade.BoardSpec;
import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → адмін: стан режиму будування барикади — увімкнено чи ні, дошки буфера (ще не
 * зареєстровані) і перша точка поточної дошки (або null). Клієнт лише МАЛЮЄ це (буфер, прев'ю);
 * рішення «що додати/видалити» приймає сервер.
 */
public record BarricadeBuildStatePacket(boolean active, List<BoardSpec> boards, Vec3 pending)
    implements S2CPacket {

    public BarricadeBuildStatePacket {
        boards = List.copyOf(boards);
    }

    public BarricadeBuildStatePacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), readBoards(buf), buf.readBoolean()
            ? new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) : null);
    }

    private static List<BoardSpec> readBoards(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<BoardSpec> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(BoardSpec.read(buf));
        return list;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeVarInt(boards.size());
        for (BoardSpec board : boards) board.write(buf);
        buf.writeBoolean(pending != null);
        if (pending != null) {
            buf.writeDouble(pending.x);
            buf.writeDouble(pending.y);
            buf.writeDouble(pending.z);
        }
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.barricade.BarricadeBuildClient.onState(active, boards, pending);
    }
}
