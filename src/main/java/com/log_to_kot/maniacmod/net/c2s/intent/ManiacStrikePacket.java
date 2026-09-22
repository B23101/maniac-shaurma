package com.log_to_kot.maniacmod.net.c2s.intent;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: маньяк тиснув ЛКМ. Без параметрів і БЕЗ цілі —
 * той самий принцип, що {@link TrapPlacePacket} ("підтверджую
 * розміщення", а куди саме — рахує сервер по погляду).
 *
 * ── Категорія: intent ────────────────────────────────────────────────
 * Одноразовий намір, той самий патерн, що {@link AbilityActivatePacket}.
 *
 * ── Чому не ванільний AttackEntityEvent ──────────────────────────────
 * Ванільна подія виникає лише тоді, коли КЛІЄНТ своїм raytrace знайшов
 * сутність під прицілом у межах ванільного pick range (~3 блоки у
 * виживанні, hardcoded, не атрибут у MC 1.20.1). Якщо дальність удару
 * архетипу (`attackRangeBlocks`) більша за це число — а конфіг дозволяє
 * аж до 8 блоків — ванільна подія просто ніколи не виникає, і дальність
 * мовчки лишається прив'язаною до ~3 блоків, хоч би що стояло в
 * конфізі. Тому клієнт шле НАМІР "я тиснув ЛКМ", а ціль (якщо вона є
 * в конусі погляду на потрібній дистанції) шукає сам сервер —
 * {@code ManiacCombatModule#findTarget}. Дальність ЗАВЖДИ рівно та, що
 * задає архетип/конфіг, незалежно від ванільного pick range.
 */
public record ManiacStrikePacket() implements ModPacket {

    public ManiacStrikePacket(FriendlyByteBuf buf) {
        this();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        // Порожньо навмисно: сервер знає й гравця (sender), і ціль
        // рахує сам — передавати нема чого.
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onManiacStrike(ModNetwork.sender(ctx));
    }
}
