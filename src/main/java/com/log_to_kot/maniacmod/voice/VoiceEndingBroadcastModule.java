package com.log_to_kot.maniacmod.voice;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Фаза ENDING: усі гравці матчу чують одне одного НЕЗАЛЕЖНО від фізичної
 * відстані на карті.
 *
 * ── Чому це не тиша й не звичайний позиційний голос ──────────────────
 * На ENDING гравці фізично розкидані (хтось у спостерігачах, хтось на
 * місці втечі) — звичайний позиційний голос не зібрав би їх у спільну
 * сцену, а тиша теж не те. Потрібен навмисно непозиційний, спільний
 * канал.
 *
 * ── Механізм (SVC API 2.5.x) ─────────────────────────────────────────
 * {@code StaticAudioChannel} — непозиційний канал, який віддає звук
 * ОДНОМУ підключенню ({@code createStaticAudioChannel(id, level, connection)}).
 * Тому на кожного учасника створюється власний канал-«приймач», а живий
 * мікрофонний потік маньяка/виживих пересилається в канали всіх ІНШИХ
 * учасників у {@link #onMicrophonePacket}.
 *
 * <p>У новіших версіях SVC API (від 2.6) той самий канал має явний список
 * цілей і {@code setBypassGroupIsolation}; тут, на 2.5.x, еквівалент
 * досягається схемою «канал на слухача» + зняття груп на ENDING
 * (див. {@code VoiceSilencePhaseModule}, який свідомо не чіпає ENDING).</p>
 *
 * ── Чому пересилання, а не запис ─────────────────────────────────────
 * {@code MicrophonePacketEvent} приносить той самий пакет, який прислав
 * клієнт, — уже з обробленим (DSP маньяка) Opus-аудіо. Мод його просто
 * дублює потрібним слухачам; жодного декодування/кодування на сервері.
 */
public final class VoiceEndingBroadcastModule {

    /** Базовий простір імен для детермінованих id каналів. */
    private static final String CHANNEL_NAMESPACE = "maniacmod:voice-ending:";

    private final VoicechatServerApi api;

    /** Слухач → його канал-«приймач». */
    private final Map<UUID, StaticAudioChannel> channels = new HashMap<>();
    /** Хто зараз бере участь у спільному ефірі. */
    private final Set<UUID> activePlayers = new HashSet<>();
    private volatile boolean running;

    public VoiceEndingBroadcastModule(VoicechatServerApi api) {
        this.api = api;
    }

    /** Вмикає спільний ефір для заданих учасників. Повторний виклик безпечний. */
    public synchronized void start(Iterable<UUID> playerIds) {
        stop();
        for (UUID playerId : playerIds) {
            VoicechatConnection connection = api.getConnectionOf(playerId);
            if (connection == null) {
                continue; // гравець без SVC — нема куди слати, і це нормально
            }
            StaticAudioChannel channel = api.createStaticAudioChannel(
                channelIdFor(playerId), connection.getPlayer().getServerLevel(), connection);
            if (channel == null) {
                continue;
            }
            channels.put(playerId, channel);
            activePlayers.add(playerId);
        }
        running = true;
    }

    /**
     * Реєструється на {@code MicrophonePacketEvent}: живий голос кожного
     * учасника пересилається каналам усіх ІНШИХ учасників. Відправнику
     * його ж голос не повертається.
     */
    public void onMicrophonePacket(MicrophonePacketEvent event) {
        if (!running) {
            return;
        }
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) {
            return;
        }
        UUID senderId = sender.getPlayer().getUuid();
        if (!activePlayers.contains(senderId)) {
            return;
        }

        MicrophonePacket packet = event.getPacket();
        List<Map.Entry<UUID, StaticAudioChannel>> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(channels.entrySet());
        }
        for (Map.Entry<UUID, StaticAudioChannel> entry : snapshot) {
            if (entry.getKey().equals(senderId)) {
                continue;
            }
            StaticAudioChannel channel = entry.getValue();
            if (channel.isClosed()) {
                continue;
            }
            channel.send(packet);
        }
    }

    /** Вимикає спільний ефір (на RESET) — канали здаються, хвіст скидається. */
    public synchronized void stop() {
        for (StaticAudioChannel channel : channels.values()) {
            try {
                channel.flush();
            } catch (RuntimeException ignored) {
                // Канал міг померти разом із підключенням — це не привід
                // не прибрати решту.
            }
        }
        channels.clear();
        activePlayers.clear();
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Детермінований id каналу на гравця: {@code StaticAudioChannel} не
     * реєструється в жодному менеджері (він лише шле пакет одному
     * підключенню), тож повторне створення з тим самим id безпечне й
     * приємно передбачуване.
     */
    private static UUID channelIdFor(UUID playerId) {
        return UUID.nameUUIDFromBytes(
            (CHANNEL_NAMESPACE + playerId).getBytes(StandardCharsets.UTF_8));
    }
}
