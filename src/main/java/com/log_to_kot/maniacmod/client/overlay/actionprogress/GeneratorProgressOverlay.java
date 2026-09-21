package com.log_to_kot.maniacmod.client.overlay.actionprogress;

import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorProgressPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Панель ремонту генератора внизу по центру екрана — референс
 * "Бензин 23%" / "Ремонт NN%": один рядок "назва зліва — відсоток
 * справа" (та сама назва, що вже дає {@code maniacmod.hud.generator.repair_word}/
 * {@code .fuel_word}), роздільник, і заглиблений прогрес-бар без
 * кутових прикрас — {@link GeneratorFuelUiTheme}, НЕ {@code ManiacUiTheme}
 * (та обслуговує інший, золотисто-акцентний стиль меню й міні-ігор).
 *
 * ── Заголовок тут — НЕ інструкція "Утримуй Shift+ПКМ" ─────────────────
 * Ця панель з'являється лише ПІСЛЯ того, як гравець уже почав тримати
 * Shift+ПКМ (сервер шле перший GeneratorProgressPacket у відповідь на
 * refreshRepair/refreshFuel) — тобто саме тоді, коли гравець УЖЕ виконує
 * дію, а не збирається її почати. Показувати тут "Утримуй Shift+ПКМ..."
 * означало б давати інструкцію про дію, що вже триває. Підказка "як
 * почати" тепер живе окремо, в {@code GeneratorHintOverlay}
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

    // БАГФІКС: панель була вдвічі більшою за решту HUD мода (порівняй
    // із RescueOverlay: PANEL_W=250, BAR_H=10, MARGIN=10) — той самий
    // текст і той самий бар не потребують удвічі ширшої/товщої панелі.
    // Розміри нижче підігнані під той самий масштаб, що RescueOverlay.
    private static final int PANEL_WIDTH = 250;
    private static final int PADDING = 10;
    private static final int TITLE_ROW_HEIGHT = 10;
    private static final int GAP_AFTER_DIVIDER = 8;
    private static final int BAR_HEIGHT = 10;
    private static final int PANEL_HEIGHT = PADDING * 2 + TITLE_ROW_HEIGHT + 6 + GAP_AFTER_DIVIDER + BAR_HEIGHT;
    private static final int BOTTOM_MARGIN = 20;

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
        int panelY = screenH - BOTTOM_MARGIN - PANEL_HEIGHT;

        GeneratorFuelUiTheme.drawPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);

        boolean repairStage = current.stage() == 0;
        int percent = Math.max(0, Math.min(100, current.stagePercent()));

        // Один рядок "назва — відсоток" (maniacmod.hud.generator.repair_word/
        // fuel_word) замість попереднього центрованого заголовка над баром
        // і окремого підпису під ним — обидва злиті в один рядок як на референсі.
        String titleWord = Component.translatable(
            repairStage ? "maniacmod.hud.generator.repair_word" : "maniacmod.hud.generator.fuel_word"
        ).getString();
        String percentText = percent + "%";

        int rowX = panelX + PADDING;
        int rowY = panelY + PADDING;
        int rowW = PANEL_WIDTH - PADDING * 2;
        GeneratorFuelUiTheme.drawTitleRow(graphics, mc.font, titleWord, percentText, rowX, rowY, rowW);

        int dividerY = rowY + TITLE_ROW_HEIGHT;
        GeneratorFuelUiTheme.divider(graphics, panelX, dividerY, PANEL_WIDTH);

        int barY = dividerY + GAP_AFTER_DIVIDER;
        int barX = panelX + PADDING;
        int barWidth = PANEL_WIDTH - PADDING * 2;

        // stagePercent приходить УЖЕ нормалізованим сервером (0-100) для обох стадій:
        // сервер знає fuelRequiredPercent, клієнт — ні. Раніше бар FUEL ділив
        // сирі відсотки на жорстке 2 і ламався, якщо fuelRequiredPercent != 200.
        GeneratorFuelUiTheme.drawBar(graphics, barX, barY, barWidth, BAR_HEIGHT, percent / 100f);
    }
}
