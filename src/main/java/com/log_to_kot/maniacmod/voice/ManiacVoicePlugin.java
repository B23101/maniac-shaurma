package com.log_to_kot.maniacmod.voice;

import com.log_to_kot.maniacmod.ManiacMod;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;

/**
 * Точка входу голосового шару в Simple Voice Chat.
 *
 * ── Як SVC знаходить цей клас ────────────────────────────────────────
 * Двома шляхами, і обидва навмисні:
 *   • {@link ForgeVoicechatPlugin} — анотація, яку читає Forge-збірка SVC
 *     (сканує всі моди й ставить клас у список плагінів). Це єдиний
 *     механізм, який реально працює на Forge 1.20.1 із наявною версією
 *     SVC API;
 *   • {@code META-INF/services/de.maxhenkel.voicechat.api.VoicechatPlugin} —
 *     ServiceLoader-реєстрація, яку використовують новіші/Fabric-подібні
 *     збірки SVC.
 * Зайвий шлях не зашкодить: SVC тримає список плагінів і викликає кожен
 * по разу; дубль у списку тут неможливий, бо це один і той самий клас і
 * один і той самий завантажувач.
 *
 * ── Що реєструється ──────────────────────────────────────────────────
 *   • {@link ClientSoundEvent} — обробка голосу маньяка на клієнті того,
 *     хто говорить (розділ 4 плану). Точка офіційна й документована.
 *   • {@link MicrophonePacketEvent} — пересилання живого мікрофона в
 *     спільний канал ENDING (розділ 8.6 плану).
 *   • {@link ClientVoicechatInitializationEvent}, {@link ClientSoundEvent} (стан «я говорю» і гучність голосу) —
 *     живлять HUD-індикатор власного голосу, див. {@link VoiceIndicatorState} і voice/README.md §7.
 *
 * ── Пріоритет обробника ──────────────────────────────────────────────
 * У цій версії SVC {@code EventBuilder} сортує обробники за СПАДАННЯМ
 * пріоритету, тобто БІЛЬШИЙ пріоритет виконується РАНІШЕ. Щоб голос
 * маньяка був фінальним і перекривав будь-який раніший обробник
 * (у т.ч. сторонній voice-changer, якщо він стоїть), потрібен НИЗЬКИЙ
 * пріоритет — тому тут від'ємне значення. Це прямо суперечить
 * поширеному «вищий пріоритет = пізніше», тому семантика перевірена по
 * коду {@code EventBuilder.build()}, а не вгадана.
 */
@ForgeVoicechatPlugin
public final class ManiacVoicePlugin implements VoicechatPlugin {

    /** Виконується останнім у ланцюжку ClientSoundEvent (див. док класу). */
    private static final int MANIAC_VOICE_PRIORITY = -1000;

    @Override
    public String getPluginId() {
        return ManiacMod.MOD_ID + ":voice";
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi serverApi) {
            // Серверна половина плагіна доступна лише на сервері (у т.ч.
            // інтегрованому). На звичайному клієнті гілка не виконується.
            VoiceRuntime.attachServerApi(serverApi);
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientSoundEvent.class,
            ManiacVoiceModule.INSTANCE::onClientSound, MANIAC_VOICE_PRIORITY);

        registration.registerEvent(MicrophonePacketEvent.class,
            VoiceRuntime::onMicrophonePacket);

        // HUD-індикатор власного голосу (замість штатних іконок SVC). Лише пишуть стан; малює client/voice.
        registration.registerEvent(ClientVoicechatInitializationEvent.class, event -> {
            VoicechatClientApi client = event.getVoicechat();
            VoiceIndicatorState.bind(client::isMuted, client::isDisabled, client::isDisconnected);
            // Та сама логіка, що в RenderEvents SVC: сховано, якщо ввімкнено hideIcons або вимкнено showHudIcons.
            VoiceIndicatorState.bindIconsHidden(() ->
                    de.maxhenkel.voicechat.VoicechatClient.CLIENT_CONFIG.hideIcons.get()
                            || !de.maxhenkel.voicechat.VoicechatClient.CLIENT_CONFIG.showHudIcons.get());
        });
        registration.registerEvent(ClientSoundEvent.class,
            event -> VoiceIndicatorState.markOwnTalking(event.isWhispering(), event.getRawAudio()));
    }
}
