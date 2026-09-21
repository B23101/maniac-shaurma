package com.log_to_kot.maniacmod.registry;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Звуки мода. Перенесено з v3 sound/ModSounds.java один-в-один —
 * список рівно той, для якого в ресурсах є .ogg файли.
 *
 * ⚠ Додавати константу сюди можна ЛИШЕ разом із файлом
 * assets/maniacmod/sounds/<name>.ogg і записом у sounds.json.
 * Реєстрація без файлу не падає — вона мовчки дає тишу, і потім
 * півгодини шукаєш, чому «звук не грає».
 */
public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ManiacMod.MOD_ID);

    // ── Матч ─────────────────────────────────────────────────────────────
    public static final RegistryObject<SoundEvent> GAME_START      = register("game_start");
    public static final RegistryObject<SoundEvent> COUNTDOWN_BEEP  = register("countdown_beep");
    public static final RegistryObject<SoundEvent> COUNTDOWN_FINAL = register("countdown_final");

    // ── Генератор ────────────────────────────────────────────────────────
    public static final RegistryObject<SoundEvent> GENERATOR_REPAIR = register("generator_repair");
    public static final RegistryObject<SoundEvent> GENERATOR_ON     = register("generator_on");
    public static final RegistryObject<SoundEvent> POWER_ON         = register("power_on");

    // ── Вихід ────────────────────────────────────────────────────────────
    public static final RegistryObject<SoundEvent> EXIT_OPEN        = register("exit_open");
    public static final RegistryObject<SoundEvent> SURVIVOR_ESCAPED = register("survivor_escaped");

    // ── Маньяк ───────────────────────────────────────────────────────────
    public static final RegistryObject<SoundEvent> MANIAC_NEARBY = register("maniac_nearby");

    // ── Пастки ───────────────────────────────────────────────────────────
    public static final RegistryObject<SoundEvent> TRAP_SNAP = register("trap_snap");
    public static final RegistryObject<SoundEvent> WIRE_ZAP  = register("wire_zap");

    // ── Стаміна ──────────────────────────────────────────────────────────
    // 3 варіації важкого дихання, коли стаміна на нулі — sounds.json
    // сам випадково обирає одну з трьох при кожному відтворенні цієї
    // події (Minecraft-механіка "sounds": [...] у визначенні події), тому
    // тут реєструється ОДИН SoundEvent на подію "exhausted_breath", а не
    // три окремих — так само, як maniac_nearby чи будь-яка інша подія
    // вище: клас-константа = одна подія в sounds.json, не один файл.
    public static final RegistryObject<SoundEvent> EXHAUSTED_BREATH = register("exhausted_breath");

    // ── Серцебиття від близькості маньяка ───────────────────────────────
    // Перенесено з мода "снайпери" (система EnhancedVisuals HeartbeatHandler):
    // два коротких удари на один цикл пульсу — OUT одразу, IN за 5 тіків
    // до кінця циклу (див. HeartbeatSoundHandler.tick()). Гучність і
    // сила залежить від того, наскільки близько маньяк — computeHeartbeat
    // у SurvivorModule вже рахує це значення 0..1, звук лише озвучує те,
    // що HUD уже показує пульсуючим серцем.
    public static final RegistryObject<SoundEvent> HEARTBEAT_IN  = register("heartbeat_in");
    public static final RegistryObject<SoundEvent> HEARTBEAT_OUT = register("heartbeat_out");

    // ── Тіло ─────────────────────────────────────────────────────────────
    // Хрускіт кісток при переломі ноги (onStandUpAttempt → BROKEN_LEG).
    public static final RegistryObject<SoundEvent> BONE_BREAK = register("bone_break");

    // TODO(асети): за дизайном ще потрібні — провал міні-гри генератора
    // (minigame_fail), залив бензину (fuel_pour). Реєструвати після
    // додавання .ogg.

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(
            new ResourceLocation(ManiacMod.MOD_ID, name)));
    }

    private ModSounds() {}
}
