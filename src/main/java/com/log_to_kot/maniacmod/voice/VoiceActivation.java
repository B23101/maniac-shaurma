package com.log_to_kot.maniacmod.voice;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.EnumSet;
import java.util.Set;

/**
 * Клієнтський місток «роль + фаза → голосовий ефект».
 *
 * ── Чому це клієнтський клас, а не PhaseListener ─────────────────────
 * Серверний {@code PhaseListener} (див. {@code core.phase}) приходить із
 * {@code List<ServerPlayer>} і за задумом керує СЕРВЕРНИМИ речами. Але
 * голос обробляється на клієнті того, хто говорить, ДО відправки на
 * сервер — тож серверний listener тут нічого не може зробити: він не
 * має доступу до PCM чужого клієнта. Тому активація прив'язана до
 * клієнтського стану матчу ({@link ClientMatchState}), який і так
 * оновлюється пакетами {@code PhaseSyncPacket}/{@code RoleSyncPacket}.
 *
 * ── Фази, у яких голос маньяка звучить ───────────────────────────────
 * Від {@code ROLE_REVEAL} (маньяк уже знає, хто він) до кінця
 * {@code ENDING} (підсумковий екран, де страшний голос — частина
 * «синематичного» ефекту перемоги). У решті фаз ефект вимкнено:
 * у лобі роль ще не роздана, у кіно/розкиданні голос не потрібен.
 */
@OnlyIn(Dist.CLIENT)
public final class VoiceActivation {

    private static final Set<GamePhase> VOICE_PHASES = EnumSet.of(
        GamePhase.ROLE_REVEAL,
        GamePhase.HUNT,
        GamePhase.POWERED,
        GamePhase.FINALE,
        GamePhase.ENDING);

    private VoiceActivation() {}

    /**
     * Перераховує стан ефекту від поточних клієнтських ролі й фази.
     * Викликається з {@code ClientPacketHandler} після оновлення
     * {@code PhaseSyncPacket} і {@code RoleSyncPacket} — тобто рівно
     * тоді, коли стан міг змінитися.
     */
    public static void refresh() {
        boolean wanted = ClientMatchState.isManiac()
            && VOICE_PHASES.contains(ClientMatchState.phase())
            && ManiacConfigs.get(ConfigSchema.VOICE_ENABLED);

        if (!wanted) {
            ManiacVoiceModule.INSTANCE.deactivate();
            return;
        }

        ManiacVoiceProfile profile = profileFor(ManiacConfigs.get(ConfigSchema.VOICE_PROFILE));
        int strength = ManiacConfigs.get(ConfigSchema.VOICE_STRENGTH);
        ManiacVoiceModule.INSTANCE.activate(profile.scaled(strength / 100.0D));
    }

    /** Ім'я пресету з конфігу → профіль. Невідоме ім'я зводиться до DEMON. */
    private static ManiacVoiceProfile profileFor(String id) {
        if (id == null) return ManiacVoiceProfile.DEMON;
        return switch (id.toUpperCase(java.util.Locale.ROOT)) {
            case "TITAN" -> ManiacVoiceProfile.TITAN;
            case "BATMAN" -> ManiacVoiceProfile.BATMAN;
            default -> ManiacVoiceProfile.DEMON;
        };
    }
}
