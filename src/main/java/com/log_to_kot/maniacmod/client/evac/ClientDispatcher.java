package com.log_to_kot.maniacmod.client.evac;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.net.s2c.evac.DispatcherPacket;
import com.log_to_kot.maniacmod.sound.OggSoundLength;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Голос диспетчера по рації: звук і текст-субтитри, що йдуть СИНХРОННО.
 *
 * <h3>Як це влаштовано</h3>
 * Сервер шле лише номер сценарію ({@link DispatcherPacket}). Сценарій — це послідовність реплік,
 * кожна — окремий файл {@code assets/maniacmod/sounds/dispatcher_<script>_<n>.ogg} і ключ тексту
 * в lang. Усі моменти старту реплік рахуються ОДИН раз від {@code t0} за реальною довжиною файлу
 * ({@link OggSoundLength}), тож:
 * <ul>
 *   <li>звук грається в момент {@code startMs[i]} (тік-підписка нижче);</li>
 *   <li>друкарський ефект ({@link DispatcherOverlay}) рахується від ТОГО Ж {@code startMs[i]} і
 *       закінчується трохи раніше за кінець файлу — текст не відстає від голосу;</li>
 *   <li>заміна .ogg на справжній запис голосу нічого не ламає: довжина читається з файлу.</li>
 * </ul>
 * Якщо файлу/запису в sounds.json немає — звук мовчки пропускається, а текст іде за запасною
 * тривалістю (за кількістю символів).
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientDispatcher {

    /** Репліка сценарію: ключ тексту і ім'я звуку (без розширення). */
    private record Line(String textKey, String sound) {}

    /** Сценарії за номером з {@link DispatcherPacket}. Новий сценарій = новий елемент списку. */
    private static final List<List<Line>> SCRIPTS = List.of(
        // 0 — CALL: після виклику вертольота з приймача
        List.of(
            new Line("maniacmod.evac.dispatcher.call.1", "dispatcher_call_1"),
            new Line("maniacmod.evac.dispatcher.call.2", "dispatcher_call_2"),
            new Line("maniacmod.evac.dispatcher.call.3", "dispatcher_call_3")));

    /** Тріск відкриття/закриття каналу (необов'язкові). */
    private static final String SQUELCH_OPEN = "dispatcher_open", SQUELCH_CLOSE = "dispatcher_close";

    /** Пауза перед першою реплікою: канал «відкривається», видно «встановлення зв'язку». */
    static final long LEAD_IN_MS = 900L;
    /** Пауза між репліками. */
    static final long GAP_MS = 500L;
    /** Тримаємо панель після останньої репліки. */
    static final long OUTRO_MS = 900L;
    /** Згасання панелі. */
    static final long FADE_OUT_MS = 500L;

    /** Один сеанс зв'язку. Усі поля незмінні після створення (окрім прапорців «вже зіграно»). */
    static final class Session {
        final long t0;
        final String[] text;
        final String[] sound;
        final long[] startMs;
        final long[] durMs;
        final boolean[] played;
        boolean openPlayed, closePlayed;
        final long endMs;
        final List<SoundInstance> instances = new ArrayList<>();

        Session(List<Line> lines, long t0) {
            this.t0 = t0;
            int n = lines.size();
            text = new String[n];
            sound = new String[n];
            startMs = new long[n];
            durMs = new long[n];
            played = new boolean[n];
            long cursor = t0 + LEAD_IN_MS;
            for (int i = 0; i < n; i++) {
                Line line = lines.get(i);
                text[i] = Component.translatable(line.textKey()).getString();
                sound[i] = line.sound();
                long fromFile = OggSoundLength.ticksOf(line.sound()) * 50L;
                // Запасна тривалість — коли файлу ще немає: приблизно темп мовлення.
                durMs[i] = fromFile > 0 ? fromFile : Math.max(1800L, text[i].length() * 62L);
                startMs[i] = cursor;
                cursor += durMs[i] + GAP_MS;
            }
            endMs = startMs[n - 1] + durMs[n - 1] + OUTRO_MS;
        }
    }

    private static Session session;

    private ClientDispatcher() {}

    /** З {@link DispatcherPacket#clientHandle()}. Новий сеанс перериває старий. */
    public static void start(int script) {
        if (script < 0 || script >= SCRIPTS.size()) return;
        stop();
        session = new Session(SCRIPTS.get(script), System.currentTimeMillis());
    }

    /** Зупиняє текст і вже запущені репліки (вихід із матчу, розрив з'єднання). */
    public static void stop() {
        Session s = session;
        session = null;
        if (s == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() == null) return;
        for (SoundInstance inst : s.instances) mc.getSoundManager().stop(inst);
    }

    static Session session() { return session; }

    // ── Звук ─────────────────────────────────────────────────────────────

    /** Тік — лише щоб вчасно запускати звук; сам текст малюється від годинника в оверлеї. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Session s = session;
        if (s == null) return;
        long now = System.currentTimeMillis();

        if (!s.openPlayed && now >= s.t0) {
            s.openPlayed = true;
            play(s, SQUELCH_OPEN, 0.8f);
        }
        for (int i = 0; i < s.played.length; i++) {
            if (!s.played[i] && now >= s.startMs[i]) {
                s.played[i] = true;
                play(s, s.sound[i], 1f);
            }
        }
        long lastEnd = s.startMs[s.startMs.length - 1] + s.durMs[s.durMs.length - 1];
        if (!s.closePlayed && now >= lastEnd + 120L) {
            s.closePlayed = true;
            play(s, SQUELCH_CLOSE, 0.8f);
        }
    }

    private static void play(Session s, String name, float volume) {
        ResourceLocation id = new ResourceLocation(ManiacMod.MOD_ID, name);
        Minecraft mc = Minecraft.getInstance();
        // Немає запису в sounds.json — тихо пропускаємо (той самий підхід, що в IntroAudio).
        if (mc.getSoundManager().getSoundEvent(id) == null) return;
        SoundInstance inst = SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), 1f, volume);
        s.instances.add(inst);
        mc.getSoundManager().play(inst);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        session = null;
    }
}
