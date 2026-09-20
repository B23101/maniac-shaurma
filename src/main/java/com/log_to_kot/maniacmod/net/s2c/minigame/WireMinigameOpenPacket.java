package com.log_to_kot.maniacmod.net.s2c.minigame;

import com.log_to_kot.maniacmod.map.minigame.WireMinigameLayout;
import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: відкрити екран міні-гри "дроти".
 *
 * {@code initialRightSlotForLeft[i]} — з яким правим контактом дріт
 * лівого контакту {@code i} з'єднаний НА СТАРТІ (навмисно неправильно,
 * жоден дріт не стоїть на своєму місці — див. {@link WireMinigameLayout}).
 * Індекс = "колір": лівий {@code i} завжди шукає правий {@code i}, а
 * як саме розфарбувати контакт {@code i} — вирішує клієнтський рендер
 * (текстура/константний список кольорів), сервер кольорів не знає.
 *
 * {@code timeLimitTicks} прийшло тут (а не читається з конфігу
 * клієнтом), щоб адмінська зміна WIRE_MINIGAME_TICKS під час матчу не
 * розсинхронила вже відкритий таймер із сервером, який рахує той
 * самий ліміт незалежно (див. {@code ActiveRepairMinigame.tickAndCheckTimeout}).
 */
public record WireMinigameOpenPacket(
        BlockPos generatorPos,
        int[] initialRightSlotForLeft,
        int timeLimitTicks
) implements S2CPacket {

    public WireMinigameOpenPacket(FriendlyByteBuf buf) {
        this(buf.readBlockPos(), readSlots(buf), buf.readVarInt());
    }

    private static int[] readSlots(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        int[] slots = new int[count];
        for (int i = 0; i < count; i++) slots[i] = buf.readVarInt();
        return slots;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(generatorPos);
        buf.writeVarInt(initialRightSlotForLeft.length);
        for (int slot : initialRightSlotForLeft) buf.writeVarInt(slot);
        buf.writeVarInt(timeLimitTicks);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onWireMinigameOpen(this);
    }
}
