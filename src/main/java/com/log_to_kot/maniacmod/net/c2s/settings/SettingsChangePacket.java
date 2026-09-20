package com.log_to_kot.maniacmod.net.c2s.settings;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: гравець змінив одне значення в
 * {@code SettingsMenuScreen}.
 *
 * ── Чому одне значення за раз, а не весь блок разом ──────────────────
 * Форма надсилає зміну одразу, коли гравець підтверджує поле (Enter,
 * стрілка +/-, клік по опції) — не чекає окремої кнопки "Зберегти всі".
 * Тому кожна зміна — власний пакет: сервер валідує/пише/перезавантажує
 * і одразу відсилає назад {@code OpenSettingsMenuPacket} з фактично
 * записаним значенням (див. докстрінг того пакета). Це та сама модель,
 * що вже в проєкті для точок карти ({@code /maniac point add} пише
 * одну точку одразу, а не збирає їх у пакет-транзакцію).
 *
 * ── Права ────────────────────────────────────────────────────────────
 * Перевірка {@code hasPermission(2)} — на СЕРВЕРІ, у
 * {@link ServerPacketHandler#onSettingsChange}, а не тут і не лише на
 * клієнті: сам факт, що екран відкрився лише оперу, не захищає від
 * модифікованого клієнта, який просто шле цей пакет без екрана.
 *
 * @param block ід блоку конфігу (ConfigSchema.blockById)
 * @param key   ім'я ключа всередині блоку (ConfigKey.name())
 * @param value нове значення, як рядок — сервер парсить і валідує
 *              (ManiacConfigs.set), клієнт нічого не перевіряє сам
 */
public record SettingsChangePacket(String block, String key, String value) implements ModPacket {

    public SettingsChangePacket(FriendlyByteBuf buf) {
        this(buf.readUtf(64), buf.readUtf(64), buf.readUtf(256));
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(block, 64);
        buf.writeUtf(key, 64);
        buf.writeUtf(value, 256);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onSettingsChange(ModNetwork.sender(ctx), block, key, value);
    }
}
