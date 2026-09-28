package com.log_to_kot.maniacmod.voice;

import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Серверний {@link PhaseListener} для спільного ефіру на ENDING.
 *
 * Окремий від {@link VoiceSilencePhaseModule} клас, а не гілки в ньому:
 * «тиша» й «спільний ефір без відстані» — протилежні за задумом режими,
 * і змішувати їх в одному місці означало б, що будь-яка правка одного
 * ламає інший.
 */
public final class VoiceEndingPhaseModule implements PhaseListener {

    private final VoiceEndingBroadcastModule ending;

    public VoiceEndingPhaseModule(VoiceEndingBroadcastModule ending) {
        this.ending = ending;
    }

    @Override
    public String id() {
        return "voice_ending";
    }

    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        if (phase == GamePhase.ENDING) {
            ending.start(ids(players));
        }
    }

    @Override
    public void onPhaseExit(GamePhase phase, List<ServerPlayer> players) {
        if (phase == GamePhase.ENDING) {
            ending.stop();
        }
    }

    /** Застосовує поточну фазу вручну при пізній реєстрації модуля. */
    public void applyCurrent(GamePhase phase, List<ServerPlayer> players) {
        if (phase == GamePhase.ENDING) {
            ending.start(ids(players));
        }
    }

    private static List<UUID> ids(List<ServerPlayer> players) {
        List<UUID> ids = new ArrayList<>(players.size());
        for (ServerPlayer player : players) {
            ids.add(player.getUUID());
        }
        return ids;
    }
}
