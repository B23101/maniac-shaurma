package com.log_to_kot.maniacmod.map.minigame;

/**
 * Дві міні-гри, які можуть випасти на гравця під час стадії REPAIR
 * (див. {@link com.log_to_kot.maniacmod.map.zones.GeneratorPoi} клас-докстрінг).
 * Яка саме випаде — вирішується випадково й рівноймовірно в
 * {@link com.log_to_kot.maniacmod.map.GeneratorModule} у момент
 * спрацювання шансу; сама модель нічого не знає про UI кожної гри.
 */
public enum RepairMinigameType {
    /** "З'єднай кольорові дроти" — 10 секунд на всі 4 дроти разом. */
    WIRES,
    /** "Влуч у ціль" — Generator Startup, рухомий повзунок, Hits: 0/N. */
    TARGET
}
