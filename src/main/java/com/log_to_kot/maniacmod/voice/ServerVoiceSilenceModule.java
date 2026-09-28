package com.log_to_kot.maniacmod.voice;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Примусова тиша на технічних фазах матчу.
 *
 * ── Механізм ─────────────────────────────────────────────────────────
 * Кожному гравцю на «тихій» фазі призначається ВЛАСНА порожня
 * {@link Group.Type#ISOLATED}-група: в ізольованій групі чують лише
 * учасників групи, а учасник один — отже, він не чує нікого й ніхто не
 * чує його. Групою керує виключно код; гравець не бачить діалогу й не
 * тисне кнопок (група {@code hidden}).
 *
 * ── Чому саме групи, а не «заглушити пакети» ─────────────────────────
 * {@code ClientSoundEvent} і {@code MicrophonePacketEvent} не є
 * {@code CancellableEvent} — вони призначені МОДИФІКУВАТИ аудіо, не
 * блокувати маршрутизацію. Спроба «заглушити» через порожній масив — це
 * недокументований хак. Серверні групи — офіційний і стабільний спосіб
 * сказати «хто кого чує».
 *
 * ── Прибирання ───────────────────────────────────────────────────────
 * Групи не зберігаються на диску ({@code persistent=false}), а SVC сам
 * видаляє невикористані непостійні групи {@code cleanupGroups()} при
 * виході гравця з групи — тому накопичення між матчами немає. Мапа
 * {@code groups} лише перевикористовує вже створену групу протягом
 * сесії (щоб не плодити їх щочотіка), а {@link #clearAll()} знімає
 * групи з усіх і чистить мапу на скиданні матчу / зупинці сервера.
 */
public final class ServerVoiceSilenceModule {

    private static final String GROUP_NAME_PREFIX = "maniacmod-silence-";

    private final VoicechatServerApi api;

    /** Персональна ISOLATED-група кожного гравця, створена ліниво. */
    private final Map<UUID, Group> silenceGroups = new HashMap<>();

    public ServerVoiceSilenceModule(VoicechatServerApi api) {
        this.api = api;
    }

    /**
     * Глушить одного гравця: створює (ліниво) його власну ізольовану
     * групу й переводить його туди. Гравець без SVC-клієнта — просто
     * немає підключення, тут нічого не робиться (це коректно: SVC
     * опційний).
     */
    public void silence(UUID playerId) {
        VoicechatConnection connection = api.getConnectionOf(playerId);
        if (connection == null) {
            return;
        }
        Group group = silenceGroups.computeIfAbsent(playerId, id ->
            api.groupBuilder()
                .setName(GROUP_NAME_PREFIX + id)
                .setType(Group.Type.ISOLATED)
                .setPersistent(false)
                .setHidden(true)
                .build());
        connection.setGroup(group);
    }

    /** Повертає гравцю звичайний (позиційний) голос. */
    public void unsilence(UUID playerId) {
        VoicechatConnection connection = api.getConnectionOf(playerId);
        if (connection != null) {
            connection.setGroup(null);
        }
        silenceGroups.remove(playerId);
    }

    /** Прибирає всі персональні групи — на RESET і на зупинці сервера. */
    public void clearAll() {
        for (UUID playerId : List.copyOf(silenceGroups.keySet())) {
            unsilence(playerId);
        }
    }
}
