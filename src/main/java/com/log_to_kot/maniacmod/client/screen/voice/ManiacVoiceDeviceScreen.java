package com.log_to_kot.maniacmod.client.screen.voice;

import com.log_to_kot.maniacmod.client.screen.voice.VoiceWidgets.VDeviceCard;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;


/**
 * Вибір мікрофона або динаміка — заміна штатних {@code SelectMicrophoneScreen} / {@code SelectSpeakerScreen}.
 * Список пристроїв і збереження вибору — ті самі, що в SVC (див. {@link SvcBridge}).
 */
public class ManiacVoiceDeviceScreen extends VoiceScreenBase {

    private final boolean microphone;

    public ManiacVoiceDeviceScreen(Screen parent, boolean microphone) {
        super(Component.translatable(microphone ? "maniacvoice.mic.title" : "maniacvoice.speaker.title"), parent);
        this.microphone = microphone;
    }

    @Override
    protected int panelWidth() {
        return 320;
    }

    @Override
    protected void build() {
        for (String device : microphone ? SvcBridge.microphones() : SvcBridge.speakers()) {
            addRow(new VDeviceCard(0, 0, rowWidth(), 30, SvcBridge.visibleDeviceName(device),
                    () -> device.equals(microphone ? SvcBridge.selectedMicrophone() : SvcBridge.selectedSpeaker()),
                    () -> {
                        if (microphone) {
                            SvcBridge.selectMicrophone(device);
                        } else {
                            SvcBridge.selectSpeaker(device);
                        }
                    }));
        }
    }

    @Override
    protected Component emptyText() {
        return Component.translatable(microphone ? "maniacvoice.mic.none" : "maniacvoice.speaker.none");
    }
}
