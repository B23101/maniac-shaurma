package com.log_to_kot.maniacmod.net.c2s.hold;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: гравець утримує ПКМ (ванільний "use"), дивлячись
 * на генератор — лагодження (стадія REPAIR).
 *
 * ── Категорія: hold ──────────────────────────────────────────────────
 * Той самий принцип, що {@link RescueHoldPacket}: важливий СТАН
 * утримання (почав/відпустив), а не одноразова подія кліку. Надсилається
 * лише на ЗМІНУ стану (натиснув / відпустив), не щотік — сервер сам
 * знає, що утримання триває, доки не прийде {@code holding=false} або
 * гравець не відійде/перестане дивитись на генератор.
 *
 * ── Чому не Entity.interact() ────────────────────────────────────────
 * {@code Entity.interact()} у ванілі спрацьовує ОДИН РАЗ на клік ПКМ,
 * а не щотік під час утримання — його вистачає лише на миттєві дії
 * (наприклад саботаж маньяка). Для "затиснути й тримати, поки не
 * набереться 100%" потрібен саме стан утримання, тому лагодження
 * винесене в окремий пакет цієї категорії, а не в
 * {@code GeneratorEntity.interact()}.
 *
 * {@code pos} — позиція генератора, на який дивиться гравець у момент
 * натискання/відпускання, визначена КЛІЄНТОМ через рейкаст (той самий
 * рейкаст, що й для будь-якої ванільної взаємодії ПКМ). Сервер не
 * бере це на віру щотік: {@code GeneratorModule} сам перевіряє
 * відстань і фазу перш ніж рахувати прогрес — цей пакет лише повідомляє
 * намір "почав/закінчив тримати", так само як {@code RescueHoldPacket}
 * не передає позицію жертви, а дає серверу знайти її самому.
 *
 * При {@code holding=false} значення {@code pos} НЕ читається сервером
 * ({@code GeneratorModule.stopRepair} закриває сесію гравця незалежно
 * від того, на яку позицію він у цей момент дивиться) — клієнт передає
 * тут технічну заглушку ({@code BlockPos.ZERO}), а не намагається
 * заново відшукати генератор, з якого щойно відвів приціл.
 */
public record GeneratorRepairHoldPacket(boolean holding, BlockPos pos) implements ModPacket {

    public GeneratorRepairHoldPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readBlockPos());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(holding);
        buf.writeBlockPos(pos);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onGeneratorRepairHold(ModNetwork.sender(ctx), holding, pos);
    }
}
