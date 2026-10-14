package com.log_to_kot.maniacmod.client.screen.voice;

import com.log_to_kot.maniacmod.client.screen.voice.VoiceWidgets.VVolumeRow;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;


/**
 * Гучність окремих гравців і категорій — заміна штатного {@code AdjustVolumesScreen} SVC.
 * Той самий склад і порядок рядків, той самий пошук і ті самі збережені значення
 * ({@code voicechat-volumes.properties}); список сам оновлюється, коли хтось заходить/виходить.
 */
public class ManiacVoiceVolumesScreen extends VoiceScreenBase {

    private static final Component TITLE = Component.translatable("maniacvoice.volumes.title");

    private EditBox search;
    private String savedSearch = "";
    private int signature;
    private int tickCounter;

    public ManiacVoiceVolumesScreen(Screen parent) {
        super(TITLE, parent);
    }

    @Override
    protected int panelWidth() {
        return 360;
    }

    @Override
    protected int topExtra() {
        return 28;
    }

    @Override
    protected boolean fillHeight() {
        return true;
    }

    @Override
    protected void build() {
        search = new EditBox(font, px + PAD + 8, py + HEADER_H + 9, pw - PAD * 2 - 22, 12,
                Component.translatable("maniacvoice.search"));
        search.setBordered(false);
        search.setMaxLength(16);
        search.setTextColor(0xFFFFFFFF);
        search.setValue(savedSearch);
        search.setResponder(text -> {
            savedSearch = text;
            replaceRows(this::fillRows);
        });
        addWidget(search);

        signature = SvcBridge.onlineSignature();
        fillRows();
    }

    private void fillRows() {
        for (SvcBridge.VolumeRow row : SvcBridge.volumeRows(search == null ? savedSearch : search.getValue())) {
            addRow(new VVolumeRow(0, 0, rowWidth(), 36, row));
        }
    }

    @Override
    protected void onTick() {
        search.tick();
        if (++tickCounter % 10 == 0) {
            int now = SvcBridge.onlineSignature();
            if (now != signature) {
                signature = now;
                replaceRows(this::fillRows);
            }
        }
    }

    @Override
    protected Component emptyText() {
        return Component.translatable("maniacvoice.search_empty");
    }

    @Override
    protected void renderTop(GuiGraphics g, int mx, int my, float pt, float a, int topY) {
        int x = px + PAD, w = pw - PAD * 2 - 6, y = topY + 3, h = 18;
        boolean focused = search.isFocused();
        g.fill(x, y, x + w, y + h, GeneratorFuelUiTheme.alpha(0xFF0B0F13, a));
        ManiacUiTheme.border1px(g, x, y, w, h, GeneratorFuelUiTheme.alpha(
                focused ? ManiacUiTheme.BORDER_BRIGHT : ManiacUiTheme.BORDER, a));
        if (focused) {
            g.fill(x + 1, y + 1, x + 3, y + h - 1, GeneratorFuelUiTheme.alpha(GeneratorFuelUiTheme.FILL, a));
        }
        if (search.getValue().isEmpty() && !focused) {
            g.drawString(font, Component.translatable("maniacvoice.search"), x + 8, y + 5,
                    GeneratorFuelUiTheme.alpha(ManiacUiTheme.TEXT_DIM, a), false);
        } else {
            search.render(g, mx, my, pt);
        }
    }

    @Override
    protected void renderOverlay(GuiGraphics g, int mx, int my, float pt) {
        for (Row r : rows()) {
            if (r.widget instanceof VVolumeRow v && v.hot(mx, my) && v.description() != null) {
                g.renderTooltip(font, Component.literal(v.description()), mx, my);
                return;
            }
        }
    }
}
