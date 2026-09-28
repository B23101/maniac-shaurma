package com.log_to_kot.maniacmod.net.c2s.intent;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: маньяк підтвердив розміщення пастки (ПКМ у режимі
 * розміщення, який відкриває клавіша 5, 6 або 7).
 *
 * ── Категорія: intent ────────────────────────────────────────────────
 * Одноразовий намір, той самий патерн, що {@link AbilityActivatePacket}.
 *
 * ── Чому тепер є {@code target} ──────────────────────────────────────
 * Раніше позиція не передавалась: сервер САМ рейкастив із положення
 * гравця в момент отримання пакета. Пакет приходить із запізненням
 * (1–3 тіки), гравець за цей час рухає мишею — сервер бачив ІНШИЙ блок,
 * ніж світив клієнтський квадрат, і пастка з'являлась не там, де куб.
 *
 * Тепер клієнт каже «ось блок, який я показував», а сервер його НЕ
 * довіряє сліпо: перевіряє, що погляд гравця справді проходить біля
 * нього ({@code TrapAiming#isLookingAtFloor}), що він у межах дальності,
 * і лише тоді ставить. Модифікований клієнт усе ще не може поставити
 * пастку через півкарти.
 *
 * @param slot        0, 1 або 2 — клавіші 5, 6, 7
 * @param secondPoint для {@code TWO_POINTS}-пасток (електричний дріт):
 *                    {@code false} — ПЕРША точка, {@code true} — ДРУГА.
 *                    Для {@code SINGLE_BLOCK}-пасток завжди {@code false}.
 * @param target      блок-підлога, який показував квадрат на клієнті
 */
public record TrapPlacePacket(int slot, boolean secondPoint, BlockPos target) implements ModPacket {

    public TrapPlacePacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readBoolean(), buf.readBlockPos());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(slot);
        buf.writeBoolean(secondPoint);
        buf.writeBlockPos(target);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onTrapPlace(ModNetwork.sender(ctx), slot, secondPoint, target);
    }
}
