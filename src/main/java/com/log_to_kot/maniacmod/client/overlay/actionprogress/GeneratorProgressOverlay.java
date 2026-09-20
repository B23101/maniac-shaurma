package com.log_to_kot.maniacmod.client.overlay.actionprogress;

import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorProgressPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Панель ремонту генератора внизу по центру екрана — референс
 * "Hold Shift+RMB to repair the generator": прогрес-бар з чіткою
 * рамкою, маленька стрілка-індикатор знизу (статична трикутна форма,
 * як на референсі — не анімована й не обертається; заголовок над баром
 * сам змінюється між стадіями ремонту й заливу бензину, стрілка лише
 * прикраса нижнього краю).
 *
 * ── Заголовок тут — НЕ інструкція "Утримуй Shift+ПКМ" ─────────────────
 * Ця панель з'являється лише ПІСЛЯ того, як гравець уже почав тримати
 * Shift+ПКМ (сервер шле перший GeneratorProgressPacket у відповідь на
 * refreshRepair/refreshFuel) — тобто саме тоді, коли гравець УЖЕ виконує
 * дію, а не збирається її почати. Показувати тут "Утримуй Shift+ПКМ..."
 * означало б давати інструкцію про дію, що вже триває. Підказка "як
 * почати" тепер живе окремо, в {@link com.log_to_kot.maniacmod.client.overlay.hint.GeneratorHintOverlay}
 * — вона малюється, лише поки гравець ДИВИТЬСЯ на генератор, але ЩЕ НЕ
 * тримає утримання (тобто ця панель і той хінт ніколи не видно
 * одночасно: як тільки утримання почалось, хінт ховається, а ця панель
 * з'являється).
 *
 * Стан приходить одним пакетом (GeneratorProgressPacket) і живе тут
 * доти, доки сервер не надішле hidden(). Оверлей нічого не рахує сам —
 * він лише малює те, що прислав сервер.
 *
 * v3-еквівалент: однойменний клас, але зі статичними полями, які
 * ніхто не скидав при виході з гри: бар лишався висіти на екрані
 * після завершення матчу.
 */
@OnlyIn(Dist.CLIENT)
public final class GeneratorProgressOverlay {

    private static GeneratorProgressPacket current = GeneratorProgressPacket.hidden();

    private static final int PANEL_WIDTH = 320;
    private static final int TITLE_HEIGHT = 34;
    private static final int BAR_HEIGHT = 16;
    private static final int BAR_MARGIN = 14;
    private static final int PANEL_HEIGHT = TITLE_HEIGHT + BAR_HEIGHT + BAR_MARGIN * 2;
    private static final int ARROW_SIZE = 8;
    private static final int BOTTOM_MARGIN = 26;

    private GeneratorProgressOverlay() {}

    public static void accept(GeneratorProgressPacket packet) {
        current = packet;
    }

    /**
     * Чи зараз видима ця панель — читається {@code GeneratorHintOverlay},
     * щоб НЕ малювати підказку "Утримуй Shift+ПКМ" одночасно з цією
     * панеллю: щойно утримання почалось і сервер прислав перший
     * прогрес-пакет, підказка "як почати" більше не потрібна.
     */
    public static boolean isVisible() {
        return current.visible();
    }

    /** Скидання при виході з матчу — викликається з ClientMatchState.reset(). */
    public static void reset() {
        current = GeneratorProgressPacket.hidden();
    }

    public static void render(GuiGraphics graphics) {
        if (!current.visible()) return;

        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int panelX = screenW / 2 - PANEL_WIDTH / 2;
        int panelY = screenH - BOTTOM_MARGIN - ARROW_SIZE - PANEL_HEIGHT;

        // Та сама панель-стиль (без кутових акцентів — вони на
        // референсі є лише у "великих" панелей на весь екран, тут
        // проста рамка+фон, як у панелі-підказки нижнього рядка).
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, ManiacUiTheme.PANEL_BG);
        ManiacUiTheme.border1px(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, ManiacUiTheme.BORDER);

        boolean repairStage = current.stage() == 0;
        Component titleLabel = Component.translatable(
            repairStage ? "maniacmod.hud.generator.repair_title" : "maniacmod.hud.generator.fuel_title");

        int titleY = panelY + BAR_MARGIN;
        graphics.drawCenteredString(mc.font, titleLabel, screenW / 2, titleY, ManiacUiTheme.TEXT_TITLE);
        ManiacUiTheme.divider(graphics, panelX + BAR_MARGIN, titleY + mc.font.lineHeight + 8, PANEL_WIDTH - BAR_MARGIN * 2);

        int barY = panelY + TITLE_HEIGHT + BAR_MARGIN;
        int barX = panelX + BAR_MARGIN;
        int barWidth = PANEL_WIDTH - BAR_MARGIN * 2;

        // stagePercent приходить УЖЕ нормалізованим сервером (0-100) для обох стадій:
        // сервер знає fuelRequiredPercent, клієнт — ні. Раніше бар FUEL ділив
        // сирі відсотки на жорстке 2 і ламався, якщо fuelRequiredPercent != 200.
        int percent = Math.max(0, Math.min(100, current.stagePercent()));
        int fillColor = repairStage ? ManiacUiTheme.BAR_FILL_WARN : 0xFF4FC3F7;
        ManiacUiTheme.drawProgressBar(graphics, barX, barY, barWidth, BAR_HEIGHT, percent / 100f, fillColor);

        Component percentLabel = repairStage
            ? Component.translatable("maniacmod.hud.generator.repair", current.stagePercent())
            : Component.translatable("maniacmod.hud.generator.fuel", current.fuelPercent());
        graphics.drawCenteredString(mc.font, percentLabel, screenW / 2, barY + (BAR_HEIGHT - mc.font.lineHeight) / 2, ManiacUiTheme.TEXT_BODY);

        if (current.minigamesTotal() > 0) {
            String stagesLabel = Component.translatable("maniacmod.hud.generator.stages_done",
                current.minigamesDone(), current.minigamesTotal()).getString();
            graphics.drawCenteredString(mc.font, stagesLabel, screenW / 2, panelY + PANEL_HEIGHT - 12, ManiacUiTheme.TEXT_DIM);
        }

        // Стрілка-індикатор — той самий елемент, що на референсі внизу
        // панелі: статична трикутна форма, кольору рамки теми.
        int arrowCenterX = screenW / 2;
        int arrowY = panelY + PANEL_HEIGHT + 6;
        drawUpArrow(graphics, arrowCenterX, arrowY, ARROW_SIZE, ManiacUiTheme.BORDER_BRIGHT);
    }

    private static void drawUpArrow(GuiGraphics graphics, int centerX, int topY, int size, int color) {
        for (int row = 0; row < size / 2; row++) {
            graphics.fill(centerX - row - 1, topY + row, centerX + row + 1, topY + row + 1, color);
        }
    }
}
