package com.log_to_kot.maniacmod.client.overlay.actionprogress;

import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorProgressPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

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
    /**
     * Голови працівників живуть У РЯДКУ ЗАГОЛОВКА (між назвою й відсотком),
     * а не в окремому рядку — тож панель не росте й не «стрибає» при появі
     * напарника. Показуються лише коли працюють двоє й більше.
     */
    private static final int HEAD_SIZE = 10;
    private static final int HEAD_GAP = 2;
    private static final int HEADS_TO_PERCENT_GAP = 6;
    private static final int MAX_HEADS = 8;
    private static final int PANEL_HEIGHT = PADDING * 2 + TITLE_ROW_HEIGHT + 6 + GAP_AFTER_DIVIDER + BAR_HEIGHT;

    /**
     * Що зараз НАМАЛЬОВАНО (плавне значення) — на відміну від {@code current},
     * де лежить те, що прислав сервер. Сервер шле лише на зміні цілого
     * відсотка (заливка ≈ 4%/с → крок кожні ~250 мс), тож малювати сирий
     * пакет означало б «сходинки». Тепер значення щокадру наближається до
     * цілі експоненційно — шкала тече, а не стрибає.
     */
    private static float shownValue = 0f;
    private static int shownStage = -1;
    private static long lastFrameNanos = 0L;

    /** Швидкість наближення, 1/с (стала часу ≈ 0.14 с). */
    private static final float SMOOTH_RATE = 7f;
    private static final int BOTTOM_MARGIN = 20;

    private GeneratorProgressOverlay() {}

    public static void accept(GeneratorProgressPacket packet) {
        // Панель щойно з'явилась або змінилась стадія (ремонт → бензин) —
        // не «доїжджаємо» зі старого значення, а стартуємо з поточного.
        if (!current.visible() || current.stage() != packet.stage()) {
            shownValue = targetOf(packet);
            shownStage = packet.stage();
            lastFrameNanos = 0L;
        }
        current = packet;
    }

    /** Ціль шкали в її власних одиницях: відсоток ремонту або сирий відсоток бензину. */
    private static float targetOf(GeneratorProgressPacket p) {
        if (p.stage() == 0) return Math.max(0, Math.min(100, p.stagePercent()));
        int required = Math.max(100, p.fuelRequired());
        return Math.max(0, Math.min(required, p.fuelPercent()));
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
        shownValue = 0f;
        shownStage = -1;
        lastFrameNanos = 0L;
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

        // Плавне значення: покадровий крок до цілі. dt обмежений, щоб пауза/лаг
        // не давали ривка.
        long nowNanos = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0f : Math.min(0.1f, (nowNanos - lastFrameNanos) / 1_000_000_000f);
        lastFrameNanos = nowNanos;
        float target = targetOf(current);
        if (shownStage != current.stage()) { shownValue = target; shownStage = current.stage(); }
        shownValue += (target - shownValue) * (1f - (float) Math.exp(-SMOOTH_RATE * dt));
        if (Math.abs(target - shownValue) < 0.02f) shownValue = target;

        // Один рядок "назва — відсоток" (maniacmod.hud.generator.repair_word/
        // fuel_word) замість попереднього центрованого заголовка над баром
        // і окремого підпису під ним — обидва злиті в один рядок як на референсі.
        String titleWord = Component.translatable(
            repairStage ? "maniacmod.hud.generator.repair_word" : "maniacmod.hud.generator.fuel_word"
        ).getString();
        // Бензин може бути більше за 100% (fuelRequiredPercent 200/300/…):
        // тоді бар ділиться на секції по 100%, а в підписі — сирий відсоток
        // (150%), бо «50%» від 200 нічого не каже про те, скільки залито.
        int fuelRequired = Math.max(100, current.fuelRequired());
        int fuelRaw = Math.max(0, Math.min(fuelRequired, current.fuelPercent()));
        boolean segmented = !repairStage && fuelRequired > 100;
        String percentText = (segmented ? fuelRaw : percent) + "%";

        int rowX = panelX + PADDING;
        int rowY = panelY + PADDING;
        int rowW = PANEL_WIDTH - PADDING * 2;
        GeneratorFuelUiTheme.drawTitleRow(graphics, mc.font, titleWord, percentText, rowX, rowY, rowW);
        drawWorkerHeads(graphics, mc.font,
            rowX + rowW - mc.font.width(percentText) - HEADS_TO_PERCENT_GAP, rowY);

        int dividerY = rowY + TITLE_ROW_HEIGHT;
        GeneratorFuelUiTheme.divider(graphics, panelX, dividerY, PANEL_WIDTH);

        int barY = dividerY + GAP_AFTER_DIVIDER;
        int barX = panelX + PADDING;
        int barWidth = PANEL_WIDTH - PADDING * 2;

        // stagePercent приходить УЖЕ нормалізованим сервером (0-100) для обох стадій:
        // сервер знає fuelRequiredPercent, клієнт — ні. Раніше бар FUEL ділив
        // сирі відсотки на жорстке 2 і ламався, якщо fuelRequiredPercent != 200.
        if (segmented) {
            drawSegmentedBar(graphics, barX, barY, barWidth, BAR_HEIGHT, shownValue, fuelRequired);
        } else {
            float denom = repairStage ? 100f : fuelRequired;
            GeneratorFuelUiTheme.drawBar(graphics, barX, barY, barWidth, BAR_HEIGHT, shownValue / denom);
        }
    }

    /**
     * Голови тих, хто зараз працює над цим генератором (ремонт або залив),
     * у рядку заголовка, притиснуті вправо до відсотка ({@code rightEdge} —
     * права межа блоку голів). Якщо працює одна людина (ти сам) — нічого не
     * малюємо: іконка має сенс лише як «ти не сам». Скін — з онлайн-гравця,
     * інакше дефолтний за UUID (як у чаті).
     */
    private static void drawWorkerHeads(GuiGraphics g, Font font, int rightEdge, int rowY) {
        java.util.List<UUID> all = current.workers();
        if (all.size() < 2) return;

        int shown = Math.min(all.size(), MAX_HEADS);
        int rowW = shown * HEAD_SIZE + (shown - 1) * HEAD_GAP;
        int x = rightEdge - rowW;
        // Текст рядка 9 px заввишки; голова 10 px — центруємо по ньому.
        int y = rowY + (font.lineHeight - HEAD_SIZE) / 2;

        for (int i = 0; i < shown; i++) {
            // Рамка 1px навколо голови, голова — всередині.
            g.fill(x, y, x + HEAD_SIZE, y + HEAD_SIZE, 0xFF56606E);
            PlayerFaceRenderer.draw(g, skinOf(all.get(i)), x + 1, y + 1, HEAD_SIZE - 2);
            x += HEAD_SIZE + HEAD_GAP;
        }
        if (all.size() > shown) {
            String more = "+" + (all.size() - shown);
            g.drawString(font, more, rightEdge - rowW - 2 - font.width(more), rowY,
                0xFFF2F4F8, true);
        }
    }

    private static ResourceLocation skinOf(UUID uuid) {
        Minecraft mc = Minecraft.getInstance();
        if (uuid != null && mc.level != null) {
            Player player = mc.level.getPlayerByUUID(uuid);
            if (player instanceof AbstractClientPlayer clientPlayer) {
                return clientPlayer.getSkinTextureLocation();
            }
        }
        return uuid == null ? DefaultPlayerSkin.getDefaultSkin() : DefaultPlayerSkin.getDefaultSkin(uuid);
    }

    /** Проміжок між секціями бара, пікселі. */
    private static final int SEGMENT_GAP = 4;

    /**
     * Бар залитого бензину, розбитий на секції по 100% у ряд: 200% —
     * дві секції, 300% — три. Перша секція заповнюється першою, друга —
     * коли перша повна, і так далі. Остання секція може бути коротшою
     * (250% → 100 + 100 + 50), її ширина пропорційна ємності.
     */
    private static void drawSegmentedBar(GuiGraphics g, int x, int y, int w, int h, float value, int required) {
        int segments = (required + 99) / 100;
        int usable = w - SEGMENT_GAP * (segments - 1);
        int cursor = x;
        int consumedCap = 0;
        for (int i = 0; i < segments; i++) {
            int cap = Math.min(100, required - i * 100);
            // Остання секція бере залишок ширини — без накопичення похибки округлення.
            int segW = (i == segments - 1)
                ? x + w - cursor
                : Math.round(usable * (float) cap / required);
            float fill = Math.max(0f, Math.min(1f, (value - consumedCap) / cap));
            GeneratorFuelUiTheme.drawBar(g, cursor, y, segW, h, fill);
            cursor += segW + SEGMENT_GAP;
            consumedCap += cap;
        }
    }
}
