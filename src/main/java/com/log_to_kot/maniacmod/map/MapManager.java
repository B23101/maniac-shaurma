package com.log_to_kot.maniacmod.map;

import com.log_to_kot.maniacmod.map.zones.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Координатор усіх просторових зон і точкових об'єктів однієї гри
 * (карти). Замінює розкидані по game/ManiacGameManager.java списки
 * (generators, exits, itemZones, escapeZones, maniacSpawnZone) —
 * тепер вони зібрані в одному місці й мають однакову форму
 * додавання/очищення.
 *
 * ManiacGameManager і надалі лишається "диригентом матчу" (хто
 * маньяк, чий хід, коли гра закінчується), але за саму КАРТУ
 * (де що розташоване) відповідає цей клас — розділення
 * відповідальності "хто грає" (ManiacGameManager) від "де це
 * відбувається" (MapManager).
 */
public class MapManager {

    private final List<GeneratorPoi> generators = new ArrayList<>();
    private final List<ExitPoi> exits = new ArrayList<>();
    private final List<ItemSpawnZoneArchetype> itemSpawnZones = new ArrayList<>();
    private final List<EscapeZoneArchetype> escapeZones = new ArrayList<>();
    private ManiacSpawnZoneArchetype maniacSpawnZone;

    // ── Реєстрація (викликається з команд /maniac addgenerator тощо) ────────

    public void addGenerator(GeneratorPoi generator)            { generators.add(generator); }
    public void addExit(ExitPoi exit)                            { exits.add(exit); }
    public void addItemSpawnZone(ItemSpawnZoneArchetype zone)    { itemSpawnZones.add(zone); }
    public void addEscapeZone(EscapeZoneArchetype zone)          { escapeZones.add(zone); }
    public void setManiacSpawnZone(ManiacSpawnZoneArchetype zone) { this.maniacSpawnZone = zone; }

    // ── Читання (викликається з ManiacGameManager під час матчу) ────────────

    public List<GeneratorPoi> generators()                   { return List.copyOf(generators); }
    public List<ExitPoi> exits()                              { return List.copyOf(exits); }
    public List<ItemSpawnZoneArchetype> itemSpawnZones()      { return List.copyOf(itemSpawnZones); }
    public List<EscapeZoneArchetype> escapeZones()            { return List.copyOf(escapeZones); }
    public ManiacSpawnZoneArchetype maniacSpawnZone()          { return maniacSpawnZone; }

    /** Скидає всю карту (викликається на /maniac clearzones). */
    public void clear() {
        generators.clear();
        exits.clear();
        itemSpawnZones.clear();
        escapeZones.clear();
        maniacSpawnZone = null;
    }
}
