package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import dev.shaurmalib.forge.sound.SoundCenter;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Важке дихання, поки стаміна виживого на нулі.
 *
 * ── Чому клієнтський тригер, не сервер + мережевий пакет ──────────────
 * Звук особистий у сенсі "тригериться на власних даних гравця" (той
 * самий стиль, що COUNTDOWN_BEEP): чути тільки самому гравцю, тому
 * немає сенсу гонити зайвий пакет через мережу щотік —
 * {@code ClientMatchState.stamina()} уже й так оновлюється щотік з
 * {@code SurvivorVitalsPacket} (єдине джерело правди для HUD стаміни
 * цього мода — той самий принцип, що привів до вимкнення дефолтного
 * бару {@code StaminaClientHooks} в {@code ClientSetup}). Тригер тут
 * лише читає те, що вже приходить.
 *
 * ── Чому позиційний звук (playAt), а не play() ─────────────────────────
 * Задишка — це звук ТІЛА гравця, тому джерело — координати самого
 * гравця ({@code SoundCenter.playAt}, {@code Attenuation.LINEAR}), а
 * не {@code SoundCenter.play()} (2D "у голові", {@code Attenuation.NONE},
 * як UI-звуки на кшталт countdown-біпів). Різниця відчутна навіть
 * соло: позиційний звук панорамується відносно напрямку камери й трохи
 * загасає, якщо гравець встиг відбігти від того місця (наприклад коли
 * стаміна впала до нуля просто перед тим, як він зупинився) — тобто
 * звучить як власне дихання гравця, а не як дикторський голос "у вухах".
 * У майбутньому мультиплеєрі з видимими іншими гравцями та ж точка
 * координат дозволить легко зробити цей звук чутним і сусіду (наразі
 * викликається лише для {@code mc.player}, тому загасання з дистанції
 * для самого джерела не відчутне — критична частина фіксу саме
 * позиція, що рухається разом із гравцем щовиклик, а не застигла
 * {@code 0,0,0} з {@code play()}).
 *
 * ── Коли задишки НЕМАЄ ────────────────────────────────────────────────
 * Лише коли стаміна на нулі саме від ВТОМИ (стан HEALTHY). При
 * {@code BROKEN_LEG} сервер тримає шкалу на нулі постійно — це штраф,
 * а не виснаження, тож задишка там звучала б без пауз до кінця матчу.
 * У CRAWLING/UNCONSCIOUS гравець не рухається взагалі — задихатись нема
 * від чого. Умова стоїть саме на СТАНІ, а не на числі стаміни: так нова
 * причина «нуль без бігу» не потребує нової правки в цьому класі.
 *
 * ── Чому не частіше MIN_INTERVAL_TICKS ─────────────────────────────────
 * Три варіації звуку (exhausted_breath_1/2/3, ~3.6–4.7с кожна) —
 * Minecraft сам випадково обирає одну з них при кожному
 * {@code SoundCenter.playAt(...)}, тому кожен виклик — це новий "видих".
 * Без інтервалу гравець зі стаміною на нулі чув би їх внапуск, накладені
 * одна на одну, щотік. MIN_INTERVAL_TICKS трохи довший за
 * найкоротший файл — наступний "видих" стартує вже після того, як
 * попередній долунав, а не поверх нього.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ExhaustedBreathClientHooks {

    /** 3.5 секунди — трохи довше за найкоротшу з трьох варіацій (3.58с), щоб копії не накладались. */
    private static final int MIN_INTERVAL_TICKS = 70;

    /** Тік клієнта, коли можна знову програти звук. 0 = ще жодного разу не грали цього виснаження. */
    private static long nextAllowedTick = 0;

    /** Чи гравець уже був на нулі стаміни минулого тіку — для миттєвого першого "видиху" при новому виснаженні. */
    private static boolean wasDepletedLastTick = false;

    private ExhaustedBreathClientHooks() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            wasDepletedLastTick = false;
            return;
        }

        // LOBBY — той самий виняток, що в SurvivorModule.onPhaseTick і
        // SurvivorVitalsOverlay: /maniac morph survivor навмисно робить
        // гравця виживим посеред лобі для дебаг-тестування HUD, і
        // vitals (зокрема stamina) там реально оновлюються щотік. Без
        // цього винятку звук задишки був відсутній рівно тоді, коли
        // тестуєш стаміну найзручніше — у лобі, без повного матчу.
        boolean vitalsAllowed = ClientMatchState.allows(PhaseRule.SURVIVOR_VITALS)
            || ClientMatchState.phase() == com.log_to_kot.maniacmod.core.phase.GamePhase.LOBBY;
        if (!ClientMatchState.isSurvivor() || !vitalsAllowed) {
            wasDepletedLastTick = false;
            return;
        }

        // Поламана нога тримає стаміну на нулі ПОСТІЙНО (це не втома, а
        // стан-штраф: шкала не відновлюється, доки не буде Шини). Якщо
        // реагувати на нуль стаміни й тут, гравець із поламаною ногою
        // чув би задишку без пауз до кінця матчу. Задишка — реакція на
        // ВИСНАЖЕННЯ бігом, тому в станах, де стаміна на нулі не через
        // біг (BROKEN_LEG; а також CRAWLING/UNCONSCIOUS, де гравець
        // взагалі не рухається), звук не грає.
        if (ClientMatchState.survivorState() != SurvivorState.HEALTHY) {
            wasDepletedLastTick = false;
            return;
        }

        boolean depleted = ClientMatchState.stamina() <= 0f;
        if (!depleted) {
            wasDepletedLastTick = false;
            return;
        }

        long tick = mc.level.getGameTime();
        // Щойно впала до нуля цього ж тіку — перший "видих" одразу, не
        // чекаючи MIN_INTERVAL_TICKS від попереднього (можливо, дуже
        // старого) відтворення.
        if (!wasDepletedLastTick) {
            nextAllowedTick = tick;
        }
        wasDepletedLastTick = true;

        if (tick < nextAllowedTick) return;

        nextAllowedTick = tick + MIN_INTERVAL_TICKS;
        // playAt (позиційно, з координат гравця), не play() (2D "у голові") —
        // звук має лунати ВІД гравця, а не як дикторський голос. Координати
        // читаються щовиклик (не закешовані), бо гравець рухається, поки
        // стаміна лишається на нулі (наприклад іде пішки після спринту).
        SoundCenter.playAt("maniacmod:exhausted_breath", SoundSource.PLAYERS, 1.0f, 1.0f,
            mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }
}
