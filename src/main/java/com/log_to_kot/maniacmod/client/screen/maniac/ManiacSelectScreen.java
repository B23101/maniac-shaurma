package com.log_to_kot.maniacmod.client.screen.maniac;

import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.selection.ManiacSelectPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Екран вибору персонажа маньяка — {@code typeMode: MENU}. Показує
 * КОЖЕН зареєстрований архетип ({@link ManiacRegistry#all()}) як
 * рядок; клік одразу шле вибір і закриває екран (на відміну від
 * {@link TrapChooseScreen}, тут немає «підтвердити» — вибір персонажа
 * не накопичується, він один).
 *
 * ── Відкриття/закриття ───────────────────────────────────────────────
 * Відкриває {@link ManiacSelectionOpener}, коли бачить порожній
 * {@code archetypeId} на фазі ROLE_REVEAL. Закривається сам одразу
 * після кліку — не чекає підтвердження від сервера (на відміну від
 * пасток, тут нема чого перевіряти локально: будь-який зареєстрований
 * архетип завжди валідний вибір).
 *
 * ── Esc ──────────────────────────────────────────────────────────────
 * Дозволено (ванільна поведінка): якщо гравець закриє екран не
 * обравши, {@link ManiacSelectionOpener} відкриє його знову наступного
 * тіку, поки триває ROLE_REVEAL і вибір не зроблено — випадковий Esc
 * не залишає маньяка без персонажа назавжди, лише відкладає вибір.
 * Якщо ROLE_REVEAL закінчиться зовсім без вибору — сервер сам
 * зобов'язаний або продовжити чекати, або призначити щось (рішення
 * поза цим екраном).
 */
public final class ManiacSelectScreen extends Screen {

    private static final int PANEL_WIDTH = 320;
    private static final int ROW_HEIGHT = 32;
    private static final int ROW_GAP = 6;

    private final List<ManiacArchetype> archetypes = new ArrayList<>(ManiacRegistry.all().values());
    private final List<int[]> rowBounds = new ArrayList<>();

    public ManiacSelectScreen() {
        super(Component.translatable("maniacmod.maniacselect.title"));
    }

    @Override
    protected void init() {
        rowBounds.clear();
        int panelX = panelX(), y = panelY() + 46;
        for (int i = 0; i < archetypes.size(); i++) {
            rowBounds.add(new int[]{panelX + 16, y, PANEL_WIDTH - 32, ROW_HEIGHT});
            y += ROW_HEIGHT + ROW_GAP;
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true; // ManiacSelectionOpener відкриє знову наступного тіку, поки вибір не зроблено
    }

    private int panelX() { return (width - PANEL_WIDTH) / 2; }
    private int panelY() { return Math.max(20, (height - panelHeight()) / 2); }

    private int panelHeight() {
        int rows = Math.max(1, archetypes.size()) * (ROW_HEIGHT + ROW_GAP) - ROW_GAP;
        return 46 + rows + 16;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int panelX = panelX(), panelY = panelY(), panelH = panelHeight();
        ManiacUiTheme.drawPanel(graphics, panelX, panelY, PANEL_WIDTH, panelH, true);
        ManiacUiTheme.drawTitle(graphics, font, getTitle(), panelX, panelY, PANEL_WIDTH);

        if (archetypes.isEmpty()) {
            // Не мало б статись (сервер уже перевірив ManiacRegistry.isEmpty()
            // перед стартом матчу), але порожній екран без пояснення був би гірше.
            graphics.drawCenteredString(font, "—", panelX + PANEL_WIDTH / 2, panelY + panelH / 2, ManiacUiTheme.TEXT_DIM);
        }

        for (int i = 0; i < archetypes.size(); i++) {
            renderRow(graphics, mouseX, mouseY, archetypes.get(i), rowBounds.get(i));
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderRow(GuiGraphics graphics, int mouseX, int mouseY, ManiacArchetype archetype, int[] bounds) {
        int x = bounds[0], y = bounds[1], w = bounds[2], h = bounds[3];
        boolean hovered = isInside(mouseX, mouseY, x, y, w, h);

        graphics.fill(x, y, x + w, y + h, ManiacUiTheme.SLOT_FILL);
        ManiacUiTheme.border1px(graphics, x, y, w, h, hovered ? ManiacUiTheme.MENU_ACCENT_GOLD : ManiacUiTheme.BORDER);

        graphics.drawString(font, archetype.displayName(), x + 12, y + (h - font.lineHeight) / 2,
            hovered ? ManiacUiTheme.TEXT_TITLE : ManiacUiTheme.TEXT_BODY, true);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (int i = 0; i < rowBounds.size(); i++) {
                int[] b = rowBounds.get(i);
                if (isInside((int) mouseX, (int) mouseY, b[0], b[1], b[2], b[3])) {
                    choose(archetypes.get(i));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void choose(ManiacArchetype archetype) {
        ModNetwork.toServer(new ManiacSelectPacket(archetype.id()));
        minecraft.setScreen(null);
    }

    private static boolean isInside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
