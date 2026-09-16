package com.log_to_kot.maniacmod.net.c2s.selection;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: маньяк обрав персонажа в меню вибору.
 *
 * ── Категорія: selection ─────────────────────────────────────────────
 * Вибір ОДНОГО варіанту з переліку в UI-меню — відрізняється і від
 * {@code intent} (клавіша дії в грі), і від {@code hold} (утримання):
 * тут клієнт явно каже "ось мій вибір", а не "я щось роблю просто
 * зараз". Якщо з'явиться вибір ролі виживого чи іншого набору
 * персонажів — новий пакет іде сюди ж.
 *
 * v3 передавав enum ManiacType ordinal — тому додати маньяка означало
 * зламати сумісність пакета. Тепер передається рядковий id архетипу:
 * новий маньяк додається без зміни мережевого формату.
 *
 * Сервер звіряє id з ManiacRegistry і ігнорує невідомий — клієнт міг
 * бути модифікований.
 */
public record ManiacSelectPacket(String maniacId) implements ModPacket {

    public ManiacSelectPacket(FriendlyByteBuf buf) {
        this(buf.readUtf());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(maniacId);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onManiacSelected(ModNetwork.sender(ctx), maniacId);
    }
}
