package com.log_to_kot.maniacmod.client.screen.voice;

import com.log_to_kot.maniacmod.client.screen.voice.VoiceWidgets.VButton;
import com.log_to_kot.maniacmod.client.screen.voice.VoiceWidgets.VCycle;
import com.log_to_kot.maniacmod.client.screen.voice.VoiceWidgets.VMeter;
import com.log_to_kot.maniacmod.client.screen.voice.VoiceWidgets.VSlider;
import com.log_to_kot.maniacmod.client.screen.voice.VoiceWidgets.VToggle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;


/**
 * Налаштування голосового чату — заміна штатного {@code VoiceChatSettingsScreen} SVC.
 *
 * Той самий набір налаштувань і ті самі конфіг-значення (див. {@link SvcBridge}): гучність, підсилення
 * мікрофона, шумоподавлення, тип активації, поріг голосу (з живим рівнем сигналу), тест мікрофона, тип
 * аудіо, гучність гравців (лише у світі), вибір мікрофона й динаміка.
 */
public class ManiacVoiceSettingsScreen extends VoiceScreenBase {

    private static final Component TITLE = Component.translatable("maniacvoice.settings.title");

    private volatile double micLevel;
    private final SvcBridge.MicTest micTest = new SvcBridge.MicTest(v -> micLevel = v);

    public ManiacVoiceSettingsScreen(net.minecraft.client.gui.screens.Screen parent) {
        super(TITLE, parent);
    }

    @Override
    protected int panelWidth() {
        return 290;
    }

    @Override
    protected void build() {
        int w = rowWidth();

        addRow(new VSlider(0, 0, w, 30, Component.translatable("maniacvoice.voice_volume"),
                SvcBridge.voiceVolume01(),
                v -> SvcBridge.voiceVolumePercent(v) + "%",
                SvcBridge::setVoiceVolume01));

        addRow(new VSlider(0, 0, w, 30, Component.translatable("maniacvoice.mic_amp"),
                SvcBridge.micAmp01(),
                v -> {
                    long gain = SvcBridge.micGainDb(v);
                    return (gain > 0 ? "+" : "") + gain + " dB";
                },
                SvcBridge::setMicAmp01));

        VToggle denoiser = addRow(new VToggle(0, 0, w, 24, Component.translatable("maniacvoice.denoiser"),
                SvcBridge::denoiser, SvcBridge::toggleDenoiser));
        denoiser.active = SvcBridge.denoiserSupported();

        addRow(new VCycle(0, 0, w, 24, Component.translatable("maniacvoice.activation"),
                SvcBridge::activationText, SvcBridge::cycleActivation));

        // Поріг — лише в режимі «голосом»; рядок плавно розкривається/ховається.
        addRow(new VSlider(0, 0, w, 30, Component.translatable("maniacvoice.threshold"),
                        SvcBridge.threshold01(),
                        v -> v >= 1D
                                ? Component.translatable("maniacvoice.threshold.never").getString()
                                : SvcBridge.thresholdDb(v) + " dB",
                        SvcBridge::setThreshold01)
                        .level(() -> micLevel)
                        .warn(v -> SvcBridge.thresholdDb(v) >= -10L),
                SvcBridge::voiceActivation);

        VButton test = addRow(new VButton(0, 0, w, 24, () -> Component.translatable(
                micTest.available()
                        ? (micTest.isOn() ? "maniacvoice.mic_test.on" : "maniacvoice.mic_test.off")
                        : "maniacvoice.mic_test.unavailable"),
                micTest::toggle).live(micTest::isOn));
        test.active = micTest.available();

        // Живий рівень голосу з міткою порога — лише поки тест мікрофона увімкнено.
        addRow(new VMeter(0, 0, w, 44, Component.translatable("maniacvoice.meter"),
                () -> micLevel, SvcBridge::threshold01, SvcBridge::voiceActivation,
                v -> String.valueOf(SvcBridge.thresholdDb(v))),
                micTest::isOn);

        addRow(new VCycle(0, 0, w, 24, Component.translatable("maniacvoice.audio_type"),
                SvcBridge::audioTypeText, () -> {
                    micTest.stop();
                    SvcBridge.cycleAudioType();
                }));

        Minecraft mc = Minecraft.getInstance();
        if (ingame()) {
            addRow(new VButton(0, 0, w, 24, Component.translatable("maniacvoice.adjust_volumes"),
                    () -> mc.setScreen(new ManiacVoiceVolumesScreen(this))));
        }
        addRow(new VButton(0, 0, w, 24, Component.translatable("maniacvoice.select_microphone"),
                () -> mc.setScreen(new ManiacVoiceDeviceScreen(this, true))));
        addRow(new VButton(0, 0, w, 24, Component.translatable("maniacvoice.select_speaker"),
                () -> mc.setScreen(new ManiacVoiceDeviceScreen(this, false))));
    }

    @Override
    protected void renderTop(GuiGraphics g, int mx, int my, float pt, float a, int topY) {
        // Штатна кнопка тесту сама «підтримує живим» потік тесту у своєму render — викликаємо щокадру.
        micTest.keepAlive(g, mx, my, pt);
    }

    @Override
    protected void onRemoved() {
        micTest.stop();
    }
}
