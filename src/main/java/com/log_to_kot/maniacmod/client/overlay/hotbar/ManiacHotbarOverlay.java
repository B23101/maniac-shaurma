package com.log_to_kot.maniacmod.client.overlay.hotbar;

import dev.shaurmalib.forge.inventory.InventorySlotAllocationClientHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Хотбар мода: слоти справа внизу за референсом (квадрат зі зрізаним
 * верхнім лівим кутом, товста сіра рамка, темний напівпрозорий фон,
 * номер слота знизу зліва) — НЕ ванільний квадрат, просто збільшений.
 * Обраний слот плавно росте до ×1.2, рамка виділення плавно ковзає
 * між слотами замість миттєвого перескоку.
 *
 * ── Чому цей клас, а не правка бібліотеки ────────────────────────────
 * shaurma-lib уже дає гачок саме для цього — {@code HotbarRenderer} +
 * {@code setCustomHotbarRenderer(...)}. Бібліотека тільки фільтрує
 * список дозволених слотів (InventorySlotAllocation) і скасовує
 * ванільний рендер; ЯК саме малювати хотбар — рішення мода.
 *
 * ── Чому скіс "сходинками", а не гладкою діагоналлю ──────────────────
 * GuiGraphics.fill малює лише прямокутники — гладкий діагональний
 * зріз без текстури або власного tesselator-виклику (тут навмисно
 * уникнутого, щоб не тягнути шейдерний стан вручну) неможливий.
 * Сходинки в 2px — той самий прийом, що дає low-res текстура
 * референсу: там скіс теж не ідеально гладкий, а трохи пікселястий.
 * Коли з'явиться готова текстура слота — renderSlotShape нижче
 * заміниться на graphics.blit(...) одним блоком, розмітка (де рамка,
 * де номер) не зміниться.
 *
 * ── Джерело правди для "обраний слот" ─────────────────────────────────
 * {@code minecraft.player.getInventory().selected} — те саме поле,
 * яке InventorySlotAllocationClientHooks.onClientTick уже примусово
 * тримає в межах дозволених слотів. Цей клас не дублює ту перевірку,
 * лише читає результат.
 */
public final class ManiacHotbarOverlay implements InventorySlotAllocationClientHooks.HotbarRenderer {

    // ── Геометрія ────────────────────────────────────────────────────────
    private static final int SLOT_SIZE = 60;
    private static final int SLOT_GAP = 8;
    private static final int MARGIN_RIGHT = 14;
    private static final int MARGIN_BOTTOM = 14;
    private static final float SELECTED_SCALE = 1.2f;

    // ── Стиль референсу ──────────────────────────────────────────────────
    private static final int BORDER_THICKNESS = 3;
    private static final int CORNER_CUT = 16;          // розмір зрізаного кута, масштабується з розміром слота
    private static final int CORNER_STEP = 2;           // товщина "сходинки" скісу
    private static final int BORDER_COLOR = 0xFFB9B9B9; // сірий, як у референсі
    private static final int BORDER_COLOR_SELECTED = 0xFFFFFFFF; // виділений слот — рамка яскравіша
    private static final int BACKGROUND_COLOR = 0xB3141414; // темний напівпрозорий фон комірки
    private static final int SLOT_NUMBER_COLOR = 0xFFDADADA;
    private static final int SELECTION_FRAME_MARGIN = 4;    // відступ рухомої обвідки від самого слота
    private static final int SELECTION_FRAME_THICKNESS = 2;

    // ── Анімація: інтерпольовані величини між кадрами ────────────────────
    private float animatedFrameIndex = 0f;
    private float animatedScale = 1f;
    private long lastFrameNanos = 0L;

    /** Наскільки швидко рамка/масштаб доганяють ціль за секунду: вище = різкіше. */
    private static final float CATCH_UP_SPEED = 14f;

    @Override
    public void render(GuiGraphics graphics, Minecraft minecraft, List<Integer> allowedSlots,
                        float partialTick, int screenWidth, int screenHeight) {
        if (minecraft.player == null || minecraft.player.isSpectator()) return;
        if (allowedSlots.isEmpty()) return;

        int selectedInventorySlot = minecraft.player.getInventory().selected;
        int selectedIndex = allowedSlots.indexOf(selectedInventorySlot);
        if (selectedIndex < 0) selectedIndex = 0;

        advanceAnimation(selectedIndex);

        int totalWidth = allowedSlots.size() * SLOT_SIZE + (allowedSlots.size() - 1) * SLOT_GAP;
        int left = screenWidth - MARGIN_RIGHT - totalWidth;
        int top = screenHeight - MARGIN_BOTTOM - SLOT_SIZE;

        for (int index = 0; index < allowedSlots.size(); index++) {
            int inventorySlot = allowedSlots.get(index);
            int slotX = left + index * (SLOT_SIZE + SLOT_GAP);
            boolean isSelected = index == selectedIndex;
            float scale = isSelected ? animatedScale : 1f;

            renderSlot(graphics, minecraft, slotX, top, inventorySlot, index + 1, scale, isSelected);
        }

        renderSelectionFrame(graphics, left, top);
    }

    /**
     * Наближає анімовані величини до цілі експоненційно за реальний
     * час між кадрами (не за тіки — HUD малюється щокадр, тіки йдуть
     * рідше й нерівномірно відносно fps).
     */
    private void advanceAnimation(int targetIndex) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        dt = Math.min(dt, 0.1f);

        float factor = 1f - (float) Math.exp(-CATCH_UP_SPEED * dt);

        animatedFrameIndex += (targetIndex - animatedFrameIndex) * factor;
        if (Math.abs(targetIndex - animatedFrameIndex) < 0.01f) animatedFrameIndex = targetIndex;

        float targetScale = SELECTED_SCALE;
        animatedScale += (targetScale - animatedScale) * factor;
    }

    private void renderSlot(GuiGraphics graphics, Minecraft minecraft, int x, int y,
                             int inventorySlot, int displayNumber, float scale, boolean isSelected) {
        int size = Math.round(SLOT_SIZE * scale);
        // Масштаб росте з центру слота, не з лівого верхнього кута —
        // інакше "збільшення" виглядало б як зсув праворуч-вниз.
        int drawX = x - (size - SLOT_SIZE) / 2;
        int drawY = y - (size - SLOT_SIZE) / 2;
        int cut = Math.round(CORNER_CUT * scale);

        int borderColor = isSelected ? BORDER_COLOR_SELECTED : BORDER_COLOR;
        int borderThickness = isSelected ? BORDER_THICKNESS + 1 : BORDER_THICKNESS;

        renderSlotShape(graphics, drawX, drawY, size, cut, borderThickness, borderColor);

        ItemStack stack = minecraft.player.getInventory().getItem(inventorySlot);
        if (!stack.isEmpty()) renderItem(graphics, minecraft, drawX, drawY, size, stack);

        String number = String.valueOf(displayNumber);
        graphics.drawString(minecraft.font, number, drawX + 5, drawY + size - 12, SLOT_NUMBER_COLOR, true);
    }

    /**
     * Квадрат зі зрізаним верхнім лівим кутом: фон і рамка одним
     * прийомом — товстий контур кольору рамки, а всередині нього (на
     * BORDER_THICKNESS менше з кожного боку) темний фон комірки. Сам
     * зріз — драбинка з горизонтальних смужок, що звужуються догори.
     */
    private void renderSlotShape(GuiGraphics graphics, int x, int y, int size, int cut,
                                  int borderThickness, int borderColor) {
        // 1) Суцільний прямокутник кольору рамки на весь квадрат,
        //    крім зрізаного кута — основа, поверх якої далі малюється
        //    внутрішній фон (тонший на borderThickness з кожного боку).
        fillWithCutCorner(graphics, x, y, size, cut, borderColor);

        // 2) Внутрішній фон — той самий прийом, зменшений на товщину
        //    рамки з усіх боків і з пропорційно меншим зрізом кута.
        int innerX = x + borderThickness;
        int innerY = y + borderThickness;
        int innerSize = size - borderThickness * 2;
        int innerCut = Math.max(0, cut - borderThickness);
        if (innerSize > 0) {
            fillWithCutCorner(graphics, innerX, innerY, innerSize, innerCut, BACKGROUND_COLOR);
        }
    }

    /** Прямокутник size×size зі зрізаним верхнім лівим кутом (драбинка кроком CORNER_STEP). */
    private void fillWithCutCorner(GuiGraphics graphics, int x, int y, int size, int cut, int color) {
        if (cut <= 0) {
            graphics.fill(x, y, x + size, y + size, color);
            return;
        }
        int step = Math.max(1, CORNER_STEP);
        int rows = Math.max(1, cut / step);
        // Верхня частина — звужені смужки (драбинка), що формують скіс.
        for (int row = 0; row < rows; row++) {
            int rowTop = y + row * step;
            int rowBottom = Math.min(y + cut, rowTop + step);
            // Що вищий рядок (ближче до самого верху), то більше зрізано зліва.
            int inset = cut - row * step;
            inset = Math.max(0, Math.min(cut, inset));
            graphics.fill(x + inset, rowTop, x + size, rowBottom, color);
        }
        // Решта прямокутника нижче зрізаної зони — суцільна.
        graphics.fill(x, y + cut, x + size, y + size, color);
    }

    private void renderItem(GuiGraphics graphics, Minecraft minecraft, int drawX, int drawY, int size, ItemStack stack) {
        // renderItem/renderItemDecorations малюють у фіксованому 16×16
        // масштабі GUI-простору — щоб предмет теж ріс разом зі слотом,
        // матрицю масштабуємо навколо центру слота перед викликом.
        var pose = graphics.pose();
        pose.pushPose();
        float itemScale = size / 24f; // трохи менше за повний розмір слота — щоб рамка лишалась видимою навколо
        float centerX = drawX + size / 2f;
        float centerY = drawY + size / 2f;
        pose.translate(centerX, centerY, 0);
        pose.scale(itemScale, itemScale, 1f);
        pose.translate(-8, -8, 0); // renderItem малює від лівого верхнього кута 16×16 іконки

        graphics.renderItem(stack, 0, 0);
        graphics.renderItemDecorations(minecraft.font, stack, 0, 0);

        pose.popPose();
    }

    /**
     * Окрема зовнішня рамка виділення, намальована ПОВЕРХ усієї сітки
     * слотів, чия X-позиція плавно ковзає до animatedFrameIndex — це і
     * є той елемент, що "рухається при зміні слота", а не миттєво
     * перескакує на новий слот. Трохи більша за сам слот (обвідка
     * зовні), щоб не зливатись із власною (миттєвою) підсвіткою рамки
     * слота в renderSlot — два незалежні шари одного ефекту виділення.
     *
     * Контур малюється чотирма прямокутними смугами замість
     * "заливка мінус внутрішня заливка": прозорий fill(color=0x00...)
     * не стирає вже намальоване під ним (альфа-блендинг, не erase),
     * тож віднімання середини таким способом не спрацювало б.
     */
    private void renderSelectionFrame(GuiGraphics graphics, int left, int top) {
        float slotCenterX = left + animatedFrameIndex * (SLOT_SIZE + SLOT_GAP) + SLOT_SIZE / 2f;
        float slotCenterY = top + SLOT_SIZE / 2f;

        int size = Math.round(SLOT_SIZE * animatedScale) + SELECTION_FRAME_MARGIN * 2;
        int x0 = Math.round(slotCenterX - size / 2f);
        int y0 = Math.round(slotCenterY - size / 2f);
        int x1 = x0 + size;
        int y1 = y0 + size;
        int t = SELECTION_FRAME_THICKNESS;

        // Верхній лівий кут теж скошений (та сама драбинка, масштабована
        // під цей трохи більший розмір) — інакше квадратний кут рамки
        // виглядав би зайвим кутом там, де сам слот під ним заокруглений.
        int cut = Math.round(CORNER_CUT * animatedScale) + SELECTION_FRAME_MARGIN;
        drawCutCornerOutline(graphics, x0, y0, x1, y1, cut, t, BORDER_COLOR_SELECTED);
    }

    /** Контур товщини t навколо прямокутника [x0,y0]-[x1,y1] зі скошеним верхнім лівим кутом. */
    private void drawCutCornerOutline(GuiGraphics graphics, int x0, int y0, int x1, int y1,
                                       int cut, int t, int color) {
        // Низ, право, ліва нижче зрізу — прості прямі смуги.
        graphics.fill(x0, y1 - t, x1, y1, color);                 // низ
        graphics.fill(x1 - t, y0 + cut, x1, y1, color);            // право (нижче скосу)
        graphics.fill(x0, y0 + cut, x0 + t, y1, color);            // ліво (нижче скосу)
        // Верх — права частина без скосу.
        graphics.fill(x0 + cut, y0, x1, y0 + t, color);
        // Скошена ділянка — та сама драбинка-контур: коротка діагональна
        // смуга завтовшки t, крок за кроком від кута до країв скосу.
        int step = Math.max(1, CORNER_STEP);
        int rows = Math.max(1, cut / step);
        for (int row = 0; row < rows; row++) {
            int rowTop = y0 + row * step;
            int rowBottom = Math.min(y0 + cut, rowTop + step);
            int inset = Math.max(0, Math.min(cut, cut - row * step));
            // Верхня межа скосу (зовнішній край драбинки).
            graphics.fill(x0 + inset, rowTop, x0 + inset + t, rowBottom, color);
        }
    }
}
