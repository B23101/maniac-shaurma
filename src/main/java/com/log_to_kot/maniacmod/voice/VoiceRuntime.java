package com.log_to_kot.maniacmod.voice;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

/**
 * Серверна «клейова» точка голосового шару.
 *
 * ── Проблема порядку, яку вона вирішує ───────────────────────────────
 * SVC і матч піднімаються незалежно: SVC кличе {@code initialize(api)}
 * зі свого плагіна, матч створюється в {@code ServerAboutToStartEvent}.
 * Хто з них буде першим — не гарантовано. Тому тут обидві сторони
 * «доносять» факт свого існування:
 *   • {@link #attachServerApi} — SVC щойно піднявся;
 *   • {@link #onServerStarted} — матч щойно створився.
 * Коли обидві події сталися, модулі фаз реєструються РІВНО ОДИН раз.
 *
 * ── Чому модулі реєструються як PhaseListener ────────────────────────
 * Тиша й спільний ефір — серверна логіка (керування групами й каналами
 * йде через {@code VoicechatServerApi}, доступний лише на сервері), тому
 * вони природно живуть у тих самих фазах, що й решта матчу.
 */
public final class VoiceRuntime {

    private static volatile VoicechatServerApi serverApi;
    private static ServerVoiceSilenceModule silence;
    private static VoiceEndingBroadcastModule ending;
    private static boolean installed;

    private VoiceRuntime() {}

    /** SVC піднявся на цьому сервері (виклик із {@code ManiacVoicePlugin.initialize}). */
    public static synchronized void attachServerApi(VoicechatServerApi api) {
        serverApi = api;
        installIfReady();
    }

    /** Матч створено його модулі зареєстровано (виклик із {@code ManiacMod}). */
    public static synchronized void onServerStarted() {
        installIfReady();
    }

    private static void installIfReady() {
        if (installed || serverApi == null) {
            return;
        }
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) {
            return; // матч ще не створено — дочекаємось onServerStarted
        }

        silence = new ServerVoiceSilenceModule(serverApi);
        ending = new VoiceEndingBroadcastModule(serverApi);

        VoiceSilencePhaseModule silenceModule = new VoiceSilencePhaseModule(silence);
        VoiceEndingPhaseModule endingModule = new VoiceEndingPhaseModule(ending);
        match.registerModule(silenceModule);
        match.registerModule(endingModule);

        // Модуль міг зареєструватися посеред матчу (SVC стартував пізніше) —
        // застосовуємо поточну фазу одразу, а не чекаємо наступної зміни.
        var currentPhase = match.phases().current();
        var online = match.onlinePlayers();
        silenceModule.applyCurrent(currentPhase, online);
        endingModule.applyCurrent(currentPhase, online);

        installed = true;
        ManiacMod.LOGGER.info("[voice] Simple Voice Chat знайдено — голосові фазові модулі зареєстровано (фаза {})",
            currentPhase);
    }

    /** Пересилання живого мікрофона для ENDING (виклик із плагіна SVC). */
    public static void onMicrophonePacket(MicrophonePacketEvent event) {
        VoiceEndingBroadcastModule current = ending;
        if (current != null) {
            current.onMicrophonePacket(event);
        }
    }

    /** Повне прибирання: зупинка сервера або кінець сесії. */
    public static synchronized void clearAll() {
        if (silence != null) {
            silence.clearAll();
            silence = null;
        }
        if (ending != null) {
            ending.stop();
            ending = null;
        }
        installed = false;
        serverApi = null;
    }
}
