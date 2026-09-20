package com.log_to_kot.maniacmod.net.s2c.settings;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Сервер → клієнт: відкрити (або оновити вже відкрите) меню
 * налаштувань гри. Летить лише ПОТОЧНІ значення, по одному рядку на
 * ключ ({@code "block.name" -> "рядкове значення"}) — метадані (тип,
 * діапазон, дефолт, опис блоку) клієнт НЕ читає з мережі: вони вже є
 * локально в {@code ConfigSchema}, бо клієнт — той самий jar мода, що
 * й сервер. Дублювати їх по мережі означало б два джерела правди
 * (одне в схемі, одне в пакеті) із тим самим ризиком розсинхрону, що
 * {@link com.log_to_kot.maniacmod.config.ConfigKey} якраз уникає для
 * читання конфігу на диску.
 *
 * Значення передаються як String (не типізовано) — {@code toString()}
 * будь-якого Integer/Double/Boolean/String парситься назад тим самим
 * {@code parseForKey}, що й запис, тому формат один в обидва боки.
 *
 * ── Коли надсилається ────────────────────────────────────────────────
 *   • у відповідь на {@code /maniac settings} — початкове відкриття;
 *   • у відповідь на кожен {@code SettingsChangePacket} — оновлення
 *     вже відкритого екрана значенням, яке РЕАЛЬНО записалось (після
 *     clamp/fallback), а не тим, що ввів гравець. Так поле в GUI
 *     завжди показує те, що насправді лежить у файлі, навіть якщо
 *     введене число вийшло за межі й було підтягнуте.
 */
public record OpenSettingsMenuPacket(Map<String, String> values) implements S2CPacket {

    public OpenSettingsMenuPacket(FriendlyByteBuf buf) {
        this(readValues(buf));
    }

    private static Map<String, String> readValues(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<String, String> map = new LinkedHashMap<>(size);
        for (int i = 0; i < size; i++) {
            map.put(buf.readUtf(64), buf.readUtf(256));
        }
        return map;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(values.size());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            buf.writeUtf(entry.getKey(), 64);
            buf.writeUtf(entry.getValue(), 256);
        }
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onOpenSettingsMenu(this);
    }
}
