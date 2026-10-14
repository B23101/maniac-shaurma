package com.log_to_kot.maniacmod.client.screen.voice;

import com.log_to_kot.maniacmod.client.screen.voice.VoiceWidgets.VButton;
import com.log_to_kot.maniacmod.client.screen.voice.VoiceWidgets.VToggle;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;


/**
 * Головне голосове меню (клавіша голосового чату) — заміна штатного {@code VoiceChatScreen} SVC.
 *
 * Зверху — живий статус (крапка, що пульсує, і еквалайзер), нижче — перемикачі «вимкнути мікрофон /
 * вимкнути голос / сховати іконки / запис» і кнопки переходу в налаштування, гучність гравців і групу
 * (групові екрани лишаються штатними SVC).
 */
public class ManiacVoiceMenuScreen extends VoiceScreenBase {

    private static final Component TITLE = Component.translatable("maniacvoice.menu.title");
    private static final int GREEN = 0xFF3E8F63;

    private VToggle mute;
    private VToggle disable;

    public ManiacVoiceMenuScreen(Screen parent) {
        super(TITLE, parent);
    }

    @Override
    protected int panelWidth() {
        return 270;
    }

    @Override
    protected int topExtra() {
        return 44;
    }

    @Override
    protected void build() {
        int w = rowWidth();
        Minecraft mc = Minecraft.getInstance();

        mute = addRow(new VToggle(0, 0, w, 24, Component.translatable("maniacvoice.menu.mute"),
                SvcBridge::muted, SvcBridge::toggleMuted));
        disable = addRow(new VToggle(0, 0, w, 24, Component.translatable("maniacvoice.menu.disable"),
                SvcBridge::disabled, SvcBridge::toggleDisabled));
        addRow(new VToggle(0, 0, w, 24, Component.translatable("maniacvoice.menu.hide_icons"),
                SvcBridge::hideIcons, SvcBridge::toggleHideIcons));

        if (SvcBridge.recordingAvailable()) {
            addRow(new VToggle(0, 0, w, 24, Component.translatable("maniacvoice.menu.record"),
                    SvcBridge::recording, SvcBridge::toggleRecording).extra(SvcBridge::recordingDuration));
        }

        addRow(new VButton(0, 0, w, 24, Component.translatable("maniacvoice.menu.settings"),
                () -> mc.setScreen(new ManiacVoiceSettingsScreen(this))));
        addRow(new VButton(0, 0, w, 24, Component.translatable("maniacvoice.adjust_volumes"),
                () -> mc.setScreen(new ManiacVoiceVolumesScreen(this))));

        refreshActive();
    }

    private void refreshActive() {
        mute.active = SvcBridge.canMute();
        disable.active = SvcBridge.canEnable();
    }

    @Override
    protected void onTick() {
        refreshActive();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (SvcBridge.isVoiceChatKey(keyCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void renderTop(GuiGraphics g, int mx, int my, float pt, float a, int topY) {
        int x = px + PAD, w = pw - PAD * 2, h = 34, y = topY + 2;

        String key;
        int color;
        float energy;
        if (SvcBridge.disconnected()) {
            key = "maniacvoice.status.disconnected";
            color = GeneratorFuelUiTheme.FILL_WARN;
            energy = 0f;
        } else if (SvcBridge.disabled()) {
            key = "maniacvoice.status.disabled";
            color = GeneratorFuelUiTheme.FILL_DANGER;
            energy = 0f;
        } else if (SvcBridge.muted()) {
            key = "maniacvoice.status.muted";
            color = GeneratorFuelUiTheme.FILL_WARN;
            energy = 0f;
        } else {
            key = "maniacvoice.status.active";
            color = GREEN;
            energy = 1f;
        }

        g.fill(x, y, x + w, y + h, GeneratorFuelUiTheme.alpha(0xFF0B0F13, a));
        ManiacUiTheme.border1px(g, x, y, w, h, GeneratorFuelUiTheme.alpha(ManiacUiTheme.BORDER, a));

        float pulse = 0.5f + 0.5f * (float) Math.sin(Util.getMillis() / (energy > 0f ? 320.0 : 700.0));
        int dotA = Math.round(255 * (0.55f + 0.45f * pulse));
        int dc = (dotA << 24) | (color & 0xFFFFFF);
        g.fill(x + 10, y + 12, x + 18, y + 20, GeneratorFuelUiTheme.alpha(dc, a));
        g.fill(x + 8, y + 10, x + 20, y + 22, GeneratorFuelUiTheme.alpha((Math.round(60 * pulse) << 24) | (color & 0xFFFFFF), a));

        Component status = Component.translatable(key);
        g.drawString(font, status.getString().toUpperCase(java.util.Locale.ROOT), x + 28, y + 8,
                GeneratorFuelUiTheme.alpha(ManiacUiTheme.TEXT_TITLE, a), false);
        g.drawString(font, SvcBridge.activationText(), x + 28, y + 20,
                GeneratorFuelUiTheme.alpha(ManiacUiTheme.TEXT_DIM, a), false);

        VoiceWidgets.drawEqualizer(g, x + w - 48, y + 8, 40, 18, energy, color, a);
    }
}
