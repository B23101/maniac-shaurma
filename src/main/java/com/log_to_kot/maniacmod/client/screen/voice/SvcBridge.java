package com.log_to_kot.maniacmod.client.screen.voice;

import de.maxhenkel.voicechat.VoicechatClient;
import de.maxhenkel.voicechat.gui.GameProfileUtils;
import de.maxhenkel.voicechat.gui.tooltips.MuteTooltipSupplier;
import de.maxhenkel.voicechat.gui.widgets.MicTestButton;
import de.maxhenkel.voicechat.intercompatibility.ClientCompatibilityManager;
import de.maxhenkel.voicechat.plugins.impl.VolumeCategoryImpl;
import de.maxhenkel.voicechat.voice.client.ClientManager;
import de.maxhenkel.voicechat.voice.client.ClientPlayerStateManager;
import de.maxhenkel.voicechat.voice.client.ClientVoicechat;
import de.maxhenkel.voicechat.voice.client.KeyEvents;
import de.maxhenkel.voicechat.voice.client.MicrophoneActivationType;
import de.maxhenkel.voicechat.voice.client.SoundManager;
import de.maxhenkel.voicechat.voice.client.microphone.MicrophoneManager;
import de.maxhenkel.voicechat.voice.client.speaker.AudioType;
import de.maxhenkel.voicechat.natives.RNNoiseManager;
import de.maxhenkel.voicechat.voice.common.AudioUtils;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * ЄДИНЕ місце, де мод торкається внутрішніх класів Simple Voice Chat.
 *
 * Усі екрани мода ({@code ManiacVoice*Screen}) говорять лише з цим класом. Якщо після оновлення SVC
 * щось перестане компілюватися — чиниться тут і тільки тут. Усі налаштування читаються й пишуться в
 * ТІ САМІ конфіги SVC ({@code voicechat-client.properties}, {@code voicechat-volumes.properties}),
 * що й у штатних меню, тож нічого не губиться й не дублюється.
 *
 * Цей клас (і все, що його викликає) вантажиться ЛИШЕ коли SVC є: {@code ManiacVoiceInterceptor}
 * перевіряє це до створення будь-якого екрана.
 */
public final class SvcBridge {

    private static final ResourceLocation OTHER_ICON = new ResourceLocation("voicechat", "textures/icons/other_volume.png");
    private static Boolean denoiserSupported;

    private SvcBridge() {}

    // ── Повзунки ─────────────────────────────────────────────────────────

    /** Гучність голосового чату 0..1 (у конфігу 0..max; max у SVC 2.6.x = 3, раніше 2). */
    public static double voiceVolume01() {
        return VoicechatClient.CLIENT_CONFIG.voiceChatVolume.get() / maxVoiceVolume();
    }

    public static void setVoiceVolume01(double v) {
        VoicechatClient.CLIENT_CONFIG.voiceChatVolume.set(v * maxVoiceVolume()).save();
    }

    /** Підпис повзунка гучності у відсотках (100% = множник 1.0, як у штатному меню). */
    public static long voiceVolumePercent(double v01) {
        return Math.round(v01 * maxVoiceVolume() * 100D);
    }

    /** Той самий максимум, що використовує штатний повзунок SVC. */
    private static double maxVoiceVolume() {
        double max = VoicechatClient.CLIENT_CONFIG.voiceChatVolume.getMax();
        ClientVoicechat client = ClientManager.getClient();
        if (client != null && client.getSoundManager() != null) {
            max = Math.min(max, client.getSoundManager().getMaxGain());
        }
        return max > 0D ? max : 1D;
    }

    /** Підсилення мікрофона 0..1. У SVC 2.6.x це microphoneGain у дБ, діапазон -40..24 (як у штатному повзунку). */
    public static double micAmp01() {
        return (VoicechatClient.CLIENT_CONFIG.microphoneGain.get() + 40D) / 64D;
    }

    public static void setMicAmp01(double v) {
        VoicechatClient.CLIENT_CONFIG.microphoneGain.set(v * 64D - 40D).save();
    }

    /** Підсилення мікрофона в дБ для підпису повзунка. */
    public static long micGainDb(double v01) {
        return Math.round(v01 * 64D - 40D);
    }

    /** Поріг активації голосом 0..1 (у конфігу — дБ). */
    public static double threshold01() {
        return AudioUtils.dbToPerc(VoicechatClient.CLIENT_CONFIG.voiceActivationThreshold.get());
    }

    public static void setThreshold01(double v) {
        VoicechatClient.CLIENT_CONFIG.voiceActivationThreshold.set(AudioUtils.percToDb(v)).save();
    }

    public static long thresholdDb(double v01) {
        return Math.round(AudioUtils.percToDb(v01));
    }

    // ── Перемикачі ───────────────────────────────────────────────────────

    public static boolean denoiser() {
        return VoicechatClient.CLIENT_CONFIG.denoiser.get();
    }

    public static void toggleDenoiser() {
        VoicechatClient.CLIENT_CONFIG.denoiser.set(!VoicechatClient.CLIENT_CONFIG.denoiser.get()).save();
    }

    public static boolean denoiserSupported() {
        if (denoiserSupported == null) {
            denoiserSupported = RNNoiseManager.canUseDenoiser();
        }
        return denoiserSupported;
    }

    public static boolean hideIcons() {
        return VoicechatClient.CLIENT_CONFIG.hideIcons.get();
    }

    public static void toggleHideIcons() {
        VoicechatClient.CLIENT_CONFIG.hideIcons.set(!VoicechatClient.CLIENT_CONFIG.hideIcons.get()).save();
    }

    public static boolean voiceActivation() {
        return MicrophoneActivationType.VOICE.equals(VoicechatClient.CLIENT_CONFIG.microphoneActivationType.get());
    }

    public static Component activationText() {
        return VoicechatClient.CLIENT_CONFIG.microphoneActivationType.get().getText();
    }

    public static void cycleActivation() {
        MicrophoneActivationType cur = VoicechatClient.CLIENT_CONFIG.microphoneActivationType.get();
        MicrophoneActivationType[] all = MicrophoneActivationType.values();
        VoicechatClient.CLIENT_CONFIG.microphoneActivationType.set(all[(cur.ordinal() + 1) % all.length]).save();
    }

    public static Component audioTypeText() {
        return VoicechatClient.CLIENT_CONFIG.audioType.get().getText();
    }

    /** Перемикає тип аудіо і перезавантажує звук (як штатна кнопка). Повертає true — тест мікрофона треба зупинити. */
    public static void cycleAudioType() {
        AudioType cur = VoicechatClient.CLIENT_CONFIG.audioType.get();
        AudioType[] all = AudioType.values();
        VoicechatClient.CLIENT_CONFIG.audioType.set(all[(cur.ordinal() + 1) % all.length]).save();
        reloadAudio();
    }

    private static void reloadAudio() {
        ClientVoicechat client = ClientManager.getClient();
        if (client != null) {
            client.reloadAudio();
        }
    }

    // ── Тест мікрофона ───────────────────────────────────────────────────

    /**
     * Тест мікрофона на ШТАТНІЙ логіці SVC: береться їхній {@code MicTestButton} (потік, динамік, лок
     * мікрофона), але приховується — малюємо ми самі. {@code render} штатної кнопки лише «підтримує
     * живим» потік тесту, тому його треба викликати кожен кадр.
     */
    public static final class MicTest {
        private final MicTestButton button;
        private boolean on;

        public MicTest(DoubleConsumer level) {
            button = new MicTestButton(0, 0, false, new MicTestButton.MicListener() {
                @Override
                public void onMicValue(double db) {
                    // у SVC 2.6.x слухач отримує дБ, а екрани мода чекають 0..1
                    level.accept(AudioUtils.dbToPerc(db));
                }

                @Override
                public void onStop() {
                    level.accept(0D);
                }
            });
            // visible МАЄ бути true: updateLastRender() у SVC 2.6.x оновлює потік тесту лише для видимої кнопки.
            // Саму кнопку не малюємо (render не викликається), малюємо рівень самі.
            button.visible = true;
        }

        public boolean available() {
            return button.active;
        }

        public boolean isOn() {
            if (!button.active) {
                on = false;
                return false;
            }
            on = button.isMicActive();
            return on;
        }

        public void toggle() {
            if (!button.active) {
                return;
            }
            button.onPress();
            on = button.isMicActive();
        }

        /** Викликати щокадру, поки екран відкритий. */
        public void keepAlive(GuiGraphics g, int mx, int my, float pt) {
            button.updateLastRender();
        }

        public void stop() {
            button.stop();
            on = false;
        }
    }

    // ── Стан голосу (головне меню) ───────────────────────────────────────

        private static ClientPlayerStateManager states() {
        return ClientManager.getPlayerStateManager();
    }

    public static boolean muted() {
        ClientPlayerStateManager s = states();
        return s != null && s.isMuted();
    }

    public static void toggleMuted() {
        ClientPlayerStateManager s = states();
        if (s != null) {
            s.setMuted(!s.isMuted());
        }
    }

    /** Мікрофон можна вимкнути лише в режимі «голосом» (як у штатному меню). */
    public static boolean canMute() {
        return MuteTooltipSupplier.canMuteMic();
    }

    public static boolean disabled() {
        ClientPlayerStateManager s = states();
        return s != null && s.isDisabled();
    }

    public static void toggleDisabled() {
        ClientPlayerStateManager s = states();
        if (s != null) {
            s.setDisabled(!s.isDisabled());
        }
    }

    public static boolean canEnable() {
        ClientPlayerStateManager s = states();
        return s != null && s.canEnable();
    }

    public static boolean disconnected() {
        ClientPlayerStateManager s = states();
        return s != null && s.isDisconnected();
    }

    public static boolean recordingAvailable() {
        ClientVoicechat client = ClientManager.getClient();
        if (client == null || !VoicechatClient.CLIENT_CONFIG.useNatives.get()) {
            return false;
        }
        return client.getRecorder() != null
                || (client.getConnection() != null && client.getConnection().getData().allowRecording());
    }

    public static boolean recording() {
        ClientVoicechat client = ClientManager.getClient();
        return client != null && client.getRecorder() != null;
    }

    public static String recordingDuration() {
        ClientVoicechat client = ClientManager.getClient();
        return client != null && client.getRecorder() != null ? client.getRecorder().getDuration() : "";
    }

    public static void toggleRecording() {
        ClientVoicechat client = ClientManager.getClient();
        if (client != null) {
            client.toggleRecording();
        }
    }

    public static boolean isVoiceChatKey(int keyCode) {
        return keyCode == ClientCompatibilityManager.INSTANCE.getBoundKeyOf(KeyEvents.KEY_VOICE_CHAT).getValue();
    }

    // ── Пристрої ─────────────────────────────────────────────────────────

    public static List<String> microphones() {
        return MicrophoneManager.deviceNames();
    }

    public static List<String> speakers() {
        return SoundManager.getAllSpeakers();
    }

    public static String selectedMicrophone() {
        return VoicechatClient.CLIENT_CONFIG.microphone.get();
    }

    public static String selectedSpeaker() {
        return VoicechatClient.CLIENT_CONFIG.speaker.get();
    }

    public static void selectMicrophone(String device) {
        VoicechatClient.CLIENT_CONFIG.microphone.set(device).save();
        reloadAudio();
    }

    public static void selectSpeaker(String device) {
        VoicechatClient.CLIENT_CONFIG.speaker.set(device).save();
        reloadAudio();
    }

    public static String visibleDeviceName(String device) {
        return SoundManager.cleanDeviceName(device);
    }

    // ── Гучність гравців ─────────────────────────────────────────────────

    /** Рядок екрана гучності: гравець, категорія або «Інше». */
    public record VolumeRow(String name, String description, ResourceLocation icon, boolean skin,
                            DoubleSupplier get, DoubleConsumer set) {}

    /** Ті самі записи, у тому ж порядку й з тим же фільтром, що в штатному списку SVC. */
    public static List<VolumeRow> volumeRows(String filter) {
        String f = filter.toLowerCase(Locale.ROOT);
        List<VolumeRow> categories = new ArrayList<>();
        List<VolumeRow> players = new ArrayList<>();

        for (VolumeCategoryImpl c : ClientManager.getCategoryManager().getCategories()) {
            if (!f.isEmpty() && !c.getName().toLowerCase(Locale.ROOT).contains(f)) {
                continue;
            }
            String id = c.getId();
            categories.add(new VolumeRow(c.getName(), c.getDescription(),
                    ClientManager.getCategoryManager().getTexture(id, OTHER_ICON), false,
                    () -> VoicechatClient.CATEGORY_VOLUME_CONFIG.getVolume(id),
                    v -> {
                        VoicechatClient.CATEGORY_VOLUME_CONFIG.setVolume(id, v);
                        VoicechatClient.CATEGORY_VOLUME_CONFIG.save();
                    }));
        }

        Collection<PlayerState> online = ClientManager.getPlayerStateManager().getPlayerStates(false);
        for (PlayerState st : online) {
            if (!f.isEmpty() && !st.getName().toLowerCase(Locale.ROOT).contains(f)) {
                continue;
            }
            players.add(playerRow(st.getUuid(), st.getName()));
        }

        if (VoicechatClient.CLIENT_CONFIG.offlinePlayerVolumeAdjustment.get()) {
            for (UUID uuid : VoicechatClient.PLAYER_VOLUME_CONFIG.getVolumes().keySet()) {
                if (uuid.equals(Util.NIL_UUID) || online.stream().anyMatch(s -> uuid.equals(s.getUuid()))) {
                    continue;
                }
                String name = VoicechatClient.USERNAME_CACHE.getUsername(uuid);
                if (name == null || (!f.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(f))) {
                    continue;
                }
                players.add(playerRow(uuid, name));
            }
        }

        categories.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        players.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));

        List<VolumeRow> out = new ArrayList<>();
        if (f.isEmpty()) {
            out.add(new VolumeRow(Component.translatable("message.voicechat.other_volume").getString(),
                    Component.translatable("message.voicechat.other_volume.description").getString(),
                    OTHER_ICON, false,
                    () -> VoicechatClient.PLAYER_VOLUME_CONFIG.getVolume(Util.NIL_UUID),
                    v -> {
                        VoicechatClient.PLAYER_VOLUME_CONFIG.setVolume(Util.NIL_UUID, v);
                        VoicechatClient.PLAYER_VOLUME_CONFIG.save();
                    }));
        }
        out.addAll(categories);
        out.addAll(players);
        return out;
    }

    private static VolumeRow playerRow(UUID uuid, String name) {
        return new VolumeRow(name, null, GameProfileUtils.getSkin(uuid), true,
                () -> VoicechatClient.PLAYER_VOLUME_CONFIG.getVolume(uuid),
                v -> {
                    VoicechatClient.PLAYER_VOLUME_CONFIG.setVolume(uuid, v);
                    VoicechatClient.PLAYER_VOLUME_CONFIG.save();
                });
    }

    /** Дешевий «підпис» списку онлайн-гравців — щоб екран знав, коли перебудувати рядки. */
    public static int onlineSignature() {
        int h = ClientManager.getCategoryManager().getCategories().size();
        for (PlayerState st : ClientManager.getPlayerStateManager().getPlayerStates(false)) {
            h = h * 31 + st.getUuid().hashCode();
        }
        return h;
    }
}
