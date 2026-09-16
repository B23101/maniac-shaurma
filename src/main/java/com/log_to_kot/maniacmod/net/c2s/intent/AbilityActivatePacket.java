package com.log_to_kot.maniacmod.net.c2s.intent;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: маньяк натиснув клавішу здібності (1, 2 або 3).
 *
 * ── Категорія: intent ────────────────────────────────────────────────
 * "Я натиснув клавішу" — одноразовий намір без прихованого стану.
 * Не плутати з {@code hold} (утримання, де важливо ПОЧАЛОСЬ/ЗАКІНЧИЛОСЬ),
 * і не {@code selection} (вибір із переліку варіантів у меню).
 *
 * Передається НОМЕР СЛОТА, а не id здібності. Слот прив'язаний до
 * клавіші й ніколи не зміниться; id здібності залежить від архетипу,
 * тому рядковий id зробив би пакет залежним від набору персонажів.
 *
 * @param slot 0, 1 або 2 — клавіші 1, 2, 3
 */
public record AbilityActivatePacket(int slot) implements ModPacket {

    public AbilityActivatePacket(FriendlyByteBuf buf) {
        this(buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(slot);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onAbilityActivate(ModNetwork.sender(ctx), slot);
    }
}
