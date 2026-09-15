package com.log_to_kot.maniacmod.sound;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ManiacMod.MOD_ID);

    // ── Гра ──────────────────────────────────────────────────────────────────
    /** Сирена/наростаючий звук на початку гри */
    public static final RegistryObject<SoundEvent> GAME_START =
        register("game_start");

    /** Відлік — один гудок кожну секунду */
    public static final RegistryObject<SoundEvent> COUNTDOWN_BEEP =
        register("countdown_beep");

    /** Останні 3 секунди відліку — тривожний гудок */
    public static final RegistryObject<SoundEvent> COUNTDOWN_FINAL =
        register("countdown_final");

    // ── Генератор ─────────────────────────────────────────────────────────────
    /** Ремонт генератора — низькочастотний гул (циклічний) */
    public static final RegistryObject<SoundEvent> GENERATOR_REPAIR =
        register("generator_repair");

    /** Генератор запущено — клацання + гул двигуна */
    public static final RegistryObject<SoundEvent> GENERATOR_ON =
        register("generator_on");

    /** Усі генератори запущено — потужний звук мережі */
    public static final RegistryObject<SoundEvent> POWER_ON =
        register("power_on");

    // ── Вихід/втеча ──────────────────────────────────────────────────────────
    /** Відкриття зони втечі — скрипіння воріт/дверей */
    public static final RegistryObject<SoundEvent> EXIT_OPEN =
        register("exit_open");

    /** Гравець втік — тріумфальний короткий звук */
    public static final RegistryObject<SoundEvent> SURVIVOR_ESCAPED =
        register("survivor_escaped");

    // ── Маньяк ────────────────────────────────────────────────────────────────
    /** Маньяк близько — низький тривожний звук (кожні 5 сек якщо маньяк < 20 бл) */
    public static final RegistryObject<SoundEvent> MANIAC_NEARBY =
        register("maniac_nearby");

    // ── Пастки ───────────────────────────────────────────────────────────────
    /** Капкан спрацював */
    public static final RegistryObject<SoundEvent> TRAP_SNAP =
        register("trap_snap");

    /** Електродріт — удар струмом */
    public static final RegistryObject<SoundEvent> WIRE_ZAP =
        register("wire_zap");

    // ─────────────────────────────────────────────────────────────────────────

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name, () ->
            SoundEvent.createVariableRangeEvent(
                new ResourceLocation(ManiacMod.MOD_ID, name)));
    }
}
