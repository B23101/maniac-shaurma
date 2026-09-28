package com.log_to_kot.maniacmod.voice;

import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Серверний {@link PhaseListener}, який глушить голос на технічних фазах.
 *
 * ── Розподіл фаз ─────────────────────────────────────────────────────
 * <ul>
 *   <li><b>Тиша</b> ({@link #SILENT_PHASES}): CINEMATIC, SCATTER,
 *       ROLE_REVEAL, RESET.</li>
 *   <li><b>Звичайний позиційний голос</b>: LOBBY, HUNT, POWERED, FINALE —
 *       тут мод не втручається взагалі.</li>
 *   <li><b>ENDING</b> — свідомо НЕ чіпається: ним керує окремий
 *       {@link VoiceEndingPhaseModule} (спільний ефір без відстані), і
 *       якби цей listener викликав тут {@code unsilence()}, він міг би
 *       зіпсувати вже налаштований спільний канал.</li>
 * </ul>
 *
 * <p>LOBBY — виняток навмисно: у лобі гравці чекають на старт і мають
 * розмовляти вільно, це звичайний соціальний етап.</p>
 */
public final class VoiceSilencePhaseModule implements PhaseListener {

    /**
     * Технічні фази, де голос глушиться повністю. LOBBY і ENDING тут
     * свідомо відсутні — див. док класу.
     */
    private static final Set<GamePhase> SILENT_PHASES = EnumSet.of(
        GamePhase.CINEMATIC,
        GamePhase.SCATTER,
        GamePhase.ROLE_REVEAL,
        GamePhase.RESET);

    private final ServerVoiceSilenceModule silence;

    public VoiceSilencePhaseModule(ServerVoiceSilenceModule silence) {
        this.silence = silence;
    }

    @Override
    public String id() {
        return "voice_silence";
    }

    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        if (SILENT_PHASES.contains(phase)) {
            for (ServerPlayer player : players) {
                silence.silence(player.getUUID());
            }
        } else if (phase != GamePhase.ENDING) {
            // LOBBY, HUNT, POWERED, FINALE — звичайний позиційний голос.
            for (ServerPlayer player : players) {
                silence.unsilence(player.getUUID());
            }
        }
    }

    @Override
    public void onPhaseExit(GamePhase phase, List<ServerPlayer> players) {
        // RESET — остання фаза матчу: після неї не лишаємо персональних
        // груп ні на сервері, ні в мапі модуля.
        if (phase == GamePhase.RESET) {
            silence.clearAll();
        }
    }

    /**
     * Застосовує поточну фазу вручну. Потрібно, коли модуль реєструється
     * ПІЗНІШЕ за вже початий матч (SVC міг піднятися після створення
     * матчу) — інакше гра вже була б, наприклад, у ROLE_REVEAL, а
     * listener ще не встиг би заглушити гравців.
     */
    public void applyCurrent(GamePhase phase, List<ServerPlayer> players) {
        onPhaseEnter(phase, players);
    }
}
