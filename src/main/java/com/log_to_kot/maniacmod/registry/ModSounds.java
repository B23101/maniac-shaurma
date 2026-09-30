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
    public static final RegistryObject<SoundEvent> POWER_ON         = register("power_on");

    // Запуск генератора — одноразовий, грає СЕРВЕР у радіусі 10 блоків
    // (GeneratorSoundscape.completed). Гул роботи тепер клієнтський —
    // client.GeneratorLoopClientSoundHandler заводить/тримає/гасить
    // AbstractTickableSoundInstance напряму з isActive() сутності
    // (це поле й так синхронізується безкоштовно), із плавним fade
    // гучності по дистанції щокадру — на відміну від старого серверного
    // персонального ресинк-лупа, мережевий рестарт семпла не вмів
    // плавно ЗАТИХАТИ (лише наростати за збігом з природним повтором).
    // ⚠ "stream": false в sounds.json — НАВМИСНО, не залишок. Стрім ламає
    // fixed-range дистанцію (клієнтський рушій рахує rolloff лише для
    // non-stream SimpleSoundInstance) — тому зациклення тут не "довгий
    // стрім-файл", а короткий non-stream семпл, який клієнт сам
    // перезапускає рівно по його реальній тривалості (OggSoundLength),
    // деталі — в client.GeneratorLoopClientSound.
    public static final RegistryObject<SoundEvent> GENERATOR_START = register("generator_start");
    public static final RegistryObject<SoundEvent> GENERATOR_LOOP  = register("generator_loop");

    // Звук заливу бензину — той самий серверний персональний ресинк
    // (sound.GeneratorFuelSoundscape), заводиться, поки
    // GeneratorEntity.stagePercent() зростає. 3 варіації (fuel_fill_1/2/3.ogg,
    // усі "stream": false тим самим причинам, що вище) — sounds.json сам
    // випадково обирає одну з трьох при кожному програванні, так само як
    // exhausted_breath.
    public static final RegistryObject<SoundEvent> FUEL_FILL = register("fuel_fill");

    // ── Вихід ────────────────────────────────────────────────────────────
    public static final RegistryObject<SoundEvent> EXIT_OPEN        = register("exit_open");
    public static final RegistryObject<SoundEvent> SURVIVOR_ESCAPED = register("survivor_escaped");

    // ── Маньяк ───────────────────────────────────────────────────────────
    public static final RegistryObject<SoundEvent> MANIAC_NEARBY = register("maniac_nearby");

    // ── Пастки ───────────────────────────────────────────────────────────
    // trap_place    — маньяк поставив пастку (звук із місця пастки);
    // trap_snap     — капкан захлопнувся на жертві;
    // trap_struggle — жертва смикається в капкані, ПОВТОРЮЄТЬСЯ поки тримає
    //                 (період = довжина файлу, тож без шва на стику —
    //                 див. TrapModule.tickStruggle);
    // crowbar_hit   — удар ломом (і по капкану, і по маньяку — той самий
    //                 звук, бо для вуха це одна дія).
    public static final RegistryObject<SoundEvent> TRAP_SNAP     = register("trap_snap");
    public static final RegistryObject<SoundEvent> TRAP_PLACE    = register("trap_place");
    public static final RegistryObject<SoundEvent> TRAP_STRUGGLE = register("trap_struggle");
    public static final RegistryObject<SoundEvent> CROWBAR_HIT   = register("crowbar_hit");
    public static final RegistryObject<SoundEvent> WIRE_ZAP      = register("wire_zap");

    // ── Електричний дріт (ТЗ 6) ────────────────────────────────────────
    // wire_place — маньяк поставив пастку;
    // wire_zap / wire_zap_2 / wire_zap_3 — ТРИ різні звуки удару, які
    //   грають ПОЧЕРГОВО під час дії струму (не випадково) — див.
    //   TrapModule.tickWire;
    // wire_cut — використання Geo-ножиць.
    // Файли додаються окремо (ТЗ 10): без них подія мовчить, помилки нема.
    public static final RegistryObject<SoundEvent> WIRE_PLACE   = register("wire_place");
    public static final RegistryObject<SoundEvent> WIRE_ZAP_2   = register("wire_zap_2");
    public static final RegistryObject<SoundEvent> WIRE_ZAP_3   = register("wire_zap_3");
    public static final RegistryObject<SoundEvent> WIRE_CUT     = register("wire_cut");

    // ── Мотузка ─────────────────────────────────────────────────────────
    // rope_place  — маньяк поставив мотузку;
    // rope_catch  — жертва зловилась і підвисла;
    // rope_escape — жертва вибралась (нитка порвалась / перерізана).
    // Свої звуки, а не капкана: мотузка звучить інакше.
    public static final RegistryObject<SoundEvent> ROPE_PLACE  = register("rope_place");
    public static final RegistryObject<SoundEvent> ROPE_CATCH  = register("rope_catch");
    public static final RegistryObject<SoundEvent> ROPE_ESCAPE = register("rope_escape");

    // Фідбек міні-гри "ціль" (ТЗ 1.2 вимагає окремого ЗВУКОВОГО відгуку
    // на попадання й на промах). Тут реєструються ЛИШЕ події —
    // файли додаються окремо (див. sounds.json): без файлу подія просто
    // не звучить, помилки нема. Міні-гра генератора й визволення з
    // капкана використовують ті самі дві події — це один екран і одна
    // механіка "зупини повзунок", відгук якої не залежить від джерела.
    public static final RegistryObject<SoundEvent> MINIGAME_HIT  = register("minigame_hit");
    public static final RegistryObject<SoundEvent> MINIGAME_MISS = register("minigame_miss");

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

    // TODO(асети): за дизайном ще потрібен провал міні-гри генератора
    // (minigame_fail). Реєструвати після додавання .ogg.

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createFixedRangeEvent(
            new ResourceLocation(ManiacMod.MOD_ID, name), 16.0f));
    }

    private ModSounds() {}
}
