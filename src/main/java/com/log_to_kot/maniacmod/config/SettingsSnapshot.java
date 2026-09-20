package com.log_to_kot.maniacmod.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Збирає поточні значення ВСІХ SETTINGS-блоків у форму, готову для
 * {@code OpenSettingsMenuPacket} — {@code "block.name" -> "рядок"}.
 *
 * ── Чому окремий клас, а не метод прямо в пакеті ─────────────────────
 * Пакет-конструктор мусить лишатись чистим (де/серіалізація), а
 * команда {@code /maniac settings} і обробник
 * {@code ServerPacketHandler.onSettingsChange} обидва потребують ТОЧНО
 * той самий знімок — один раз при відкритті, другий раз одразу після
 * запису одного значення. Дублювати цикл по {@code ConfigSchema.BLOCKS}
 * у двох місцях — саме те дублювання, від якого застерігає клас-докстрінг
 * {@code ConfigSchema}.
 *
 * DATA-блоки (SPAWN_POINTS, ZONES) свідомо не входять — у них немає
 * фіксованих ключів зі схемою, редагувати їх формою нема чим (та й
 * нема сенсу: вони наповнюються командами розмітки на місцевості, а
 * не числами).
 */
public final class SettingsSnapshot {

    private SettingsSnapshot() {}

    public static Map<String, String> collect() {
        Map<String, String> values = new LinkedHashMap<>();
        for (ConfigBlock block : ConfigSchema.BLOCKS) {
            if (block.kind() != ConfigBlock.Kind.SETTINGS) continue;
            for (ConfigKey<?> key : block.keys()) {
                values.put(key.path(), String.valueOf(ManiacConfigs.get(key)));
            }
        }
        return values;
    }
}
