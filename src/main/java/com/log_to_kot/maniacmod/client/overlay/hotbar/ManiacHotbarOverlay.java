package com.log_to_kot.maniacmod.client.overlay.hotbar;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import dev.shaurmalib.forge.inventory.InventorySlotAllocationClientHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Хотбар мода: слоти справа внизу.
 *
 * ── Форма слота: ПРЯМИЙ квадрат, суцільна заливка ────────────────────
 * Слот — прямий (без зрізаного кута) квадрат {@link #SLOT_FILL}
 * ({@code 0xFF0B0B0B}), з тонкою рамкою по всьому периметру. Раніше
 * тут був зрізаний у верхньому лівому куті контур без заливки фону
 * (крізь слот було видно світ) — це визнано проблемою (плутало форму
 * слота й ховало вміст на світлому фоні), тому й заливку, і прямий
 * квадрат повернуто.
 * Вибраний слот — товща рамка яскравішого кольору поверх звичайної, той
 * самий прямий контур, без пунктиру (пунктир існував лише для зрізаної
 * діагоналі, якої більше немає).
 *
 * ── Три типи слотів — і чому вони розрізнені кольором акценту ────────
 * Маньяк одночасно бачить слот удару (коло), до 3 слотів здібностей
 * (1/2/3) і до 3 слотів пасток (Z/X/C) — {@link ManiacKeybinds}. Досі
 * усі неспеціальні слоти маньяка малювались ОДНИМ і тим самим виглядом
 * (renderManiacSlot), тому пастка й здібність були візуально
 * невідмінні одна від одної — гравець мусив пам'ятати порядок слотів
 * напам'ять. Тепер кожен тип має власний акцентний колір рамки/обводки
 * готовності (жовтогарячий — пастка, голубий — здібність, білий —
 * звичайний предмет виживого), той самий прямий квадрат для всіх.
 *
 * ── Форма й рендер предметів ──────────────────────────────────────────
 * Усе геометрія малюється Forge-примітивами ({@code GuiGraphics#fill}),
 * жодних PNG для форми/рамки/прогресу. Сам предмет у слоті — звичайний
 * рендер {@code ItemStack}.
 *
 * ── Заряди сили (3 кола навколо слота удару) ───────────────────────
 * {@link #powerCharges} — заглушка (завжди 3/3): жодного джерела даних
 * на сервері ще немає. Замінити на реальне поле, коли з'явиться
 * відповідний s2c-пакет — сама відмальовка не зміниться.
 *
 * ── Кулдаун пасток ─────────────────────────────────────────────────
 * Сервер має лише ОДИН спільний {@code trapPlaceCooldownTicks}
 * (ConfigSchema), не по-слотний — тому для трьох слотів пасток
 * малюється той самий {@link ClientMatchState#abilityCooldownFraction}
 * під одним спільним id {@link #TRAP_COOLDOWN_ID}, яке
 * {@code ServerHooks}/{@code TrapPlacePacket} мають виставляти при
 * кожній спробі поставити пастку (той самий канал, що вже існує для
 * здібностей — {@code AbilityCooldownPacket} — жодного нового пакета не
 * бракує, досить слати той самий з id = "trap_cooldown").
 */
public final class ManiacHotbarOverlay implements InventorySlotAllocationClientHooks.HotbarRenderer {

    // ── Геометрія ──────────────────────────────────────────────────────
    private static final int SLOT_SIZE = 60;
    private static final int SLOT_GAP = 8;
    private static final int MARGIN_RIGHT = 14;
    private static final int MARGIN_BOTTOM = 14;
    private static final float SELECTED_SCALE = 1.2f;

    /** Товщина рамки слота (невибраного). */
    private static final int BORDER_THICKNESS = 1;
    /** Товщина рамки вибору (поверх звичайної, на весь периметр). */
    private static final int SELECTION_THICKNESS = 2;

    // ── Кольори (ARGB) ────────────────────────────────────────────────
    /** Суцільна темна заливка фону кожного слота — і виживого, і маньяка. Спільне джерело з {@link ManiacUiTheme#SLOT_FILL}. */
    private static final int SLOT_FILL = ManiacUiTheme.SLOT_FILL;

    // Звичайний предмет виживого — нейтральний білий, як на референсі.
    private static final int PLAYER_BORDER = 0x90FFFFFF;
    private static final int PLAYER_SELECTED = 0xFFFFFFFF;

    // Удар маньяка — той самий нейтральний білий (це базова зброя, не спец-слот).
    private static final int STRIKE_BORDER = 0x90FFFFFF;
    private static final int STRIKE_GLYPH_COLOR = 0xFFEDEDED;

    // Здібності маньяка — акцентний холодний блакитний.
    private static final int ABILITY_BORDER = 0x9059C7FF;
    private static final int ABILITY_SELECTED = 0xFF59C7FF;
    private static final int ABILITY_PROGRESS = 0xFF59C7FF;

    // Пастки маньяка — акцентний жовтогарячий.
    private static final int TRAP_BORDER = 0x90FF9838;
    private static final int TRAP_SELECTED = 0xFFFF9838;
    private static final int TRAP_PROGRESS = 0xFFFF9838;

    private static final int POWER_CHARGE_LIT = 0xFFFFD24A;
    private static final int POWER_CHARGE_UNLIT = 0x50666666;

    private static final int STRIKE_ICON_SIZE = 32;
    private static final int POWER_CHARGE_SIZE = 24;
    private static final int POWER_CHARGE_ORBIT_RADIUS = 27;

    private static final int SLOT_NUMBER_COLOR = 0xFFDADADA;

    /** id під яким сервер шле спільний кулдаун пасток через той самий канал, що здібності (AbilityCooldownPacket). */
    private static final String TRAP_COOLDOWN_ID = "trap_cooldown";

    /**
     * Конвенція слот маньяка → тип. index 0 завжди слот удару (окремий
     * рендер, коло). Індекси 1-3 — здібності 1/2/3, індекси 4-6 —
     * пастки Z/X/C (за {@code ManiacKeybinds}: рівно по 3 кожного типу).
     * Ability id для слотів здібностей поки не прив'язаний до жодного
     * реального {@code Ability} (жоден маньяк ще не повертає непорожній
     * {@code abilities()}) — тому {@link #SLOT_ABILITY_IDS} лишається
     * заповнювачем null, аналогічно попередній версії; коли прив'язка
     * з'явиться, обводка готовності стане реальною автоматично.
     */
    private enum SlotKind { STRIKE, ABILITY, TRAP }

    private static final String[] SLOT_ABILITY_IDS = { null, null, null };

    private float animatedFrameIndex = 0f;
    private float animatedScale = 1f;
    private long lastFrameNanos = 0L;

    private static final float CATCH_UP_SPEED = 14f;

    private static final int MAX_POWER_CHARGES = 3;
    private int powerCharges = MAX_POWER_CHARGES;

    @Override
    public void render(GuiGraphics graphics, Minecraft minecraft, List<Integer> allowedSlots,
                        float partialTick, int screenWidth, int screenHeight) {
        if (minecraft.player == null || minecraft.player.isSpectator()) return;
        if (allowedSlots.isEmpty()) return;

        boolean maniac = ClientMatchState.isManiac();

        int selectedInventorySlot = minecraft.player.getInventory().selected;
        int selectedIndex = allowedSlots.indexOf(selectedInventorySlot);
        if (selectedIndex < 0) selectedIndex = 0;

        advanceAnimation(selectedIndex);

        int totalWidth = allowedSlots.size() * SLOT_SIZE + (allowedSlots.size() - 1) * SLOT_GAP;
        int left = screenWidth - MARGIN_RIGHT - totalWidth;
        int top = screenHeight - MARGIN_BOTTOM - SLOT_SIZE;

        long currentTick = minecraft.level != null ? minecraft.level.getGameTime() : 0L;

        // Прохід 1: базові слоти. Кожен слот малює лише СВІЙ статичний
        // (невибраний) вигляд — тонкий контур, прогрес готовності,
        // предмет. Рамка вибору — окремий шар (прохід 2), що плавно
        // ковзає між слотами, а не перемальовується як частина слота —
        // інакше вона "стрибала" б миттєво замість плавного ковзання.
        for (int index = 0; index < allowedSlots.size(); index++) {
            int inventorySlot = allowedSlots.get(index);
            int slotX = left + index * (SLOT_SIZE + SLOT_GAP);

            if (maniac && index == 0) {
                renderStrikeSlot(graphics, minecraft, slotX, top, inventorySlot, index + 1);
            } else if (maniac) {
                SlotKind kind = classifyManiacSlot(index);
                renderManiacSlot(graphics, minecraft, slotX, top, inventorySlot, index + 1, kind, currentTick);
            } else {
                renderPlayerSlot(graphics, minecraft, slotX, top, inventorySlot, index + 1);
            }
        }

        // Прохід 2: ковзна рамка вибору поверх усього — колір залежить
        // від типу ЩОЙНО вибраного слота (маньяк: удар/здібність/пастка
        // кожен має власний акцент), звичайний виживий завжди білий.
        SlotKind selectedKind = maniac ? (selectedIndex == 0 ? SlotKind.STRIKE : classifyManiacSlot(selectedIndex))
                : SlotKind.STRIKE; // STRIKE тут лише позначає "нейтральний білий колір", виживий не має типів
        int selectionColor = !maniac ? PLAYER_SELECTED
                : switch (selectedKind) {
                    case TRAP -> TRAP_SELECTED;
                    case ABILITY -> ABILITY_SELECTED;
                    case STRIKE -> STRIKE_GLYPH_COLOR;
                };
        renderSelectionFrame(graphics, left, top, maniac && selectedIndex == 0, selectionColor);
    }

    /** index 0 = удар (не входить сюди), 1..3 = здібності, 4..6 = пастки (див. клас-докстрінг). */
    private static SlotKind classifyManiacSlot(int index) {
        return index <= 3 ? SlotKind.ABILITY : SlotKind.TRAP;
    }

    /**
     * Рамка вибору — окремий шар, що плавно ковзає між центрами слотів
     * ({@link #animatedFrameIndex}) замість миттєвого перемикання.
     * Коли вибраний слот — круглий слот удару, рамка теж кругла (щоб не
     * "різати" кути по прямокутному контуру навколо круга); інакше —
     * той самий прямий квадрат, що й звичайні слоти, лише товщий і
     * яскравіший.
     */
    private void renderSelectionFrame(GuiGraphics graphics, int left, int top, boolean isStrikeSlot, int color) {
        float slotCenterX = left + animatedFrameIndex * (SLOT_SIZE + SLOT_GAP) + SLOT_SIZE / 2f;
        float slotCenterY = top + SLOT_SIZE / 2f;
        int size = Math.round(SLOT_SIZE * animatedScale);
        int drawX = Math.round(slotCenterX - size / 2f);
        int drawY = Math.round(slotCenterY - size / 2f);

        if (isStrikeSlot) {
            strokeCircle(graphics, Math.round(slotCenterX), Math.round(slotCenterY), size / 2, color, 2);
        } else {
            strokeSquare(graphics, drawX, drawY, size, SELECTION_THICKNESS, color);
        }
    }

    private void advanceAnimation(int targetIndex) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        dt = Math.min(dt, 0.1f);

        float factor = 1f - (float) Math.exp(-CATCH_UP_SPEED * dt);

        animatedFrameIndex += (targetIndex - animatedFrameIndex) * factor;
        if (Math.abs(targetIndex - animatedFrameIndex) < 0.01f) animatedFrameIndex = targetIndex;

        animatedScale += (SELECTED_SCALE - animatedScale) * factor;
    }

    // ── Виживий: звичайний слот ────────────────────────────────────────

    private void renderPlayerSlot(GuiGraphics graphics, Minecraft minecraft, int x, int y,
                                   int inventorySlot, int displayNumber) {
        graphics.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_FILL);
        strokeSquare(graphics, x, y, SLOT_SIZE, BORDER_THICKNESS, PLAYER_BORDER);
        drawSlotContents(graphics, minecraft, x, y, inventorySlot, displayNumber);
    }

    // ── Маньяк: слот здібності або пастки ─────────────────────────────

    private void renderManiacSlot(GuiGraphics graphics, Minecraft minecraft, int x, int y,
                                   int inventorySlot, int displayNumber, SlotKind kind, long currentTick) {
        int border = kind == SlotKind.TRAP ? TRAP_BORDER : ABILITY_BORDER;
        int progressColor = kind == SlotKind.TRAP ? TRAP_PROGRESS : ABILITY_PROGRESS;

        graphics.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_FILL);
        strokeSquare(graphics, x, y, SLOT_SIZE, BORDER_THICKNESS, border);

        float cooldownFraction = readCooldownFraction(kind, displayNumber, currentTick);
        float readyFraction = clamp01(1f - cooldownFraction);
        if (readyFraction > 0f && readyFraction < 1f) {
            strokeProgressSquare(graphics, x, y, SLOT_SIZE, progressColor, readyFraction);
        }

        drawSlotContents(graphics, minecraft, x, y, inventorySlot, displayNumber);
    }

    private float readCooldownFraction(SlotKind kind, int displayNumber, long currentTick) {
        if (kind == SlotKind.TRAP) {
            return ClientMatchState.abilityCooldownFraction(TRAP_COOLDOWN_ID, currentTick);
        }
        int abilityIndex = displayNumber - 2; // displayNumber 2..4 -> index 0..2 (1 займає удар)
        String abilityId = abilityIndex >= 0 && abilityIndex < SLOT_ABILITY_IDS.length
                ? SLOT_ABILITY_IDS[abilityIndex] : null;
        return abilityId == null ? 0f : ClientMatchState.abilityCooldownFraction(abilityId, currentTick);
    }

    // ── Маньяк: слот удару (круглий) + 3 заряди сили по колу ──────────

    private void renderStrikeSlot(GuiGraphics graphics, Minecraft minecraft, int x, int y,
                                   int inventorySlot, int displayNumber) {
        int centerX = x + SLOT_SIZE / 2;
        int centerY = y + SLOT_SIZE / 2;
        int radius = SLOT_SIZE / 2;

        fillCircle(graphics, centerX, centerY, radius, SLOT_FILL);
        strokeCircle(graphics, centerX, centerY, radius, STRIKE_BORDER, 1);
        drawStrikeGlyph(graphics, centerX, centerY, STRIKE_ICON_SIZE, STRIKE_GLYPH_COLOR);

        String number = String.valueOf(displayNumber);
        graphics.drawString(minecraft.font, number, x + 5, y + SLOT_SIZE - 11, SLOT_NUMBER_COLOR, true);

        renderPowerCharges(graphics, x + SLOT_SIZE / 2f, y + SLOT_SIZE / 2f, 1f);
    }

    private static void drawStrikeGlyph(GuiGraphics g, int centerX, int centerY, int iconSize, int color) {
        float half = iconSize / 2f;
        double angle = Math.toRadians(45);
        double perp = angle + Math.PI / 2;
        float claw1 = 0.90f;
        float[] offsets = { -claw1 * half * 0.33f, 0f, claw1 * half * 0.33f };

        for (float offset : offsets) {
            float baseCx = centerX + (float) (Math.cos(perp) * offset) - (float) (Math.cos(angle) * half * 0.85f);
            float baseCy = centerY + (float) (Math.sin(perp) * offset) - (float) (Math.sin(angle) * half * 0.85f);
            float tipX = centerX + (float) (Math.cos(perp) * offset) + (float) (Math.cos(angle) * half * 0.85f);
            float tipY = centerY + (float) (Math.sin(perp) * offset) + (float) (Math.sin(angle) * half * 0.85f);
            drawTaperedLine(g, baseCx, baseCy, tipX, tipY, half * 0.16f, color);
        }
    }

    private static void drawTaperedLine(GuiGraphics g, float x0, float y0, float x1, float y1, float baseHalfWidth, int color) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) return;
        float ux = dx / len, uy = dy / len;
        float px = -uy, py = ux;

        int steps = Math.max(4, Math.round(len));
        for (int i = 0; i < steps; i++) {
            float t = i / (float) steps;
            float tNext = (i + 1) / (float) steps;
            float halfW = baseHalfWidth * (1f - t) + 0.4f;
            float cx = x0 + dx * (t + tNext) / 2f;
            float cy = y0 + dy * (t + tNext) / 2f;
            int x = Math.round(cx - px * halfW);
            int y = Math.round(cy - py * halfW);
            int w = Math.max(1, Math.round(halfW * 2));
            g.fill(x, y, x + Math.max(1, Math.round(ux * (len / steps)) + 1), y + w, color);
        }
    }

    private void renderPowerCharges(GuiGraphics graphics, float centerX, float centerY, float scale) {
        float orbitRadius = POWER_CHARGE_ORBIT_RADIUS * scale;
        int chargeRadius = Math.max(2, Math.round(POWER_CHARGE_SIZE * scale / 2f));

        for (int i = 0; i < MAX_POWER_CHARGES; i++) {
            double angle = Math.toRadians(-90 + i * 120);
            int chargeCenterX = Math.round(centerX + (float) (orbitRadius * Math.cos(angle)));
            int chargeCenterY = Math.round(centerY + (float) (orbitRadius * Math.sin(angle)));

            boolean lit = i < powerCharges;
            fillCircle(graphics, chargeCenterX, chargeCenterY, chargeRadius, lit ? POWER_CHARGE_LIT : POWER_CHARGE_UNLIT);
            strokeCircle(graphics, chargeCenterX, chargeCenterY, chargeRadius, STRIKE_BORDER, 1);
        }
    }

    // ── Спільне: контур слота + вміст ─────────────────────────────────

    private void drawSlotContents(GuiGraphics graphics, Minecraft minecraft, int x, int y,
                                   int inventorySlot, int displayNumber) {
        int itemSize = Math.round(SLOT_SIZE * 0.72f);
        int itemX = x + (SLOT_SIZE - itemSize) / 2;
        int itemY = y + (SLOT_SIZE - itemSize) / 2;

        ItemStack stack = minecraft.player.getInventory().getItem(inventorySlot);
        if (!stack.isEmpty()) renderItem(graphics, minecraft, itemX, itemY, itemSize, stack);

        String number = String.valueOf(displayNumber);
        graphics.drawString(minecraft.font, number, x + 5, y + SLOT_SIZE - 11, SLOT_NUMBER_COLOR, true);
    }

    // ── Геометрія: прямий квадрат ─────────────────────────────────────
    //
    // Периметр обходиться за тим самим порядком точок, що раніше мав
    // зрізаний варіант (top -> right -> bottom -> left, за годинниковою
    // стрілкою від лівого верхнього кута), тому "0..1 по периметру" в
    // strokeProgressSquare означає той самий напрямок обходу, що й
    // раніше — лише без діагонального зрізу.

    /** Контур квадрата товщиною {@code thickness}, суцільною лінією по всіх 4 сторонах. */
    private static void strokeSquare(GuiGraphics g, int x, int y, int size, int thickness, int color) {
        int x1 = x + size, y1 = y + size;
        g.fill(x, y, x1, y + thickness, color);              // top
        g.fill(x, y1 - thickness, x1, y1, color);             // bottom
        g.fill(x, y, x + thickness, y1, color);                // left
        g.fill(x1 - thickness, y, x1, y1, color);              // right
    }

    /** Прогрес по периметру прямого квадрата (0..1), той самий порядок обходу, що раніше мав зрізаний варіант. */
    private static void strokeProgressSquare(GuiGraphics g, int x, int y, int size, int color, float fraction) {
        int x0 = x, y0 = y, x1 = x + size, y1 = y + size;

        float topLen = size, rightLen = size, bottomLen = size, leftLen = size;
        float total = topLen + rightLen + bottomLen + leftLen;

        float remaining = clamp01(fraction) * total;

        float seg = Math.min(remaining, topLen);
        if (seg > 0) g.fill(x0, y0, x0 + Math.round(seg), y0 + 1, color);
        remaining -= topLen;
        if (remaining <= 0) return;

        seg = Math.min(remaining, rightLen);
        if (seg > 0) g.fill(x1 - 1, y0, x1, y0 + Math.round(seg), color);
        remaining -= rightLen;
        if (remaining <= 0) return;

        seg = Math.min(remaining, bottomLen);
        if (seg > 0) g.fill(x1 - Math.round(seg), y1 - 1, x1, y1, color);
        remaining -= bottomLen;
        if (remaining <= 0) return;

        seg = Math.min(remaining, leftLen);
        if (seg > 0) g.fill(x0, y1 - Math.round(seg), x0 + 1, y1, color);
    }

    private static void fillCircle(GuiGraphics g, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
            g.fill(cx - dx, cy + dy, cx + dx, cy + dy + 1, color);
        }
    }

    private static void strokeCircle(GuiGraphics g, int cx, int cy, int radius, int color, int thickness) {
        int steps = Math.max(24, radius * 2);
        for (int t = 0; t < thickness; t++) {
            int r = radius - t;
            if (r <= 0) continue;
            for (int i = 0; i < steps; i++) {
                double angle = 2 * Math.PI * i / steps;
                int px = cx + (int) Math.round(r * Math.cos(angle));
                int py = cy + (int) Math.round(r * Math.sin(angle));
                g.fill(px, py, px + 1, py + 1, color);
            }
        }
    }

    // ── Спільне ──────────────────────────────────────────────────────

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private void renderItem(GuiGraphics graphics, Minecraft minecraft, int drawX, int drawY, int size, ItemStack stack) {
        var pose = graphics.pose();
        pose.pushPose();
        float itemScale = size / 24f;
        float centerX = drawX + size / 2f;
        float centerY = drawY + size / 2f;
        pose.translate(centerX, centerY, 0);
        pose.scale(itemScale, itemScale, 1f);
        pose.translate(-8, -8, 0);

        graphics.renderItem(stack, 0, 0);
        graphics.renderItemDecorations(minecraft.font, stack, 0, 0);

        pose.popPose();
    }
}
