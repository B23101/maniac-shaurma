package com.log_to_kot.maniacmod.client.overlay.hotbar;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import dev.shaurmalib.forge.inventory.InventorySlotAllocationClientHooks;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
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

    // ── Палітра: стиль панелей генератора (срібло на темному) ───────────
    // Тип слота маньяка читається тонкою кольоровою смужкою зверху слота
    // (здібність — срібло, пастка — бурштин); сама рамка й вибір — єдині
    // для всіх, як у GeneratorFuelUiTheme.
    private static final int SLOT_BORDER = GeneratorFuelUiTheme.PANEL_BORDER;
    private static final int PLAYER_SELECTED = GeneratorFuelUiTheme.HIGHLIGHT;
    private static final int STRIKE_BORDER = GeneratorFuelUiTheme.PANEL_BORDER;
    private static final int STRIKE_GLYPH_COLOR = GeneratorFuelUiTheme.FILL;
    private static final int ABILITY_STRIP = GeneratorFuelUiTheme.FILL;
    private static final int TRAP_STRIP = GeneratorFuelUiTheme.FILL_WARN;
    private static final int SELECTION_COLOR = GeneratorFuelUiTheme.HIGHLIGHT;

    private static final int POWER_CHARGE_LIT = GeneratorFuelUiTheme.FILL;
    private static final int POWER_CHARGE_UNLIT = 0x50666666;

    private static final int STRIKE_ICON_SIZE = 32;
    private static final int POWER_CHARGE_SIZE = 24;
    private static final int POWER_CHARGE_ORBIT_RADIUS = 27;

    private static final int SLOT_NUMBER_COLOR = GeneratorFuelUiTheme.HIGHLIGHT;

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

    /** Кадровий dt останнього advanceAnimation — для спалахів готовності. */
    private float frameDt = 0.016f;
    /** Спалах готовності по індексах слотів (1 → 0), запускається, коли кулдаун закінчився. */
    private final float[] readyFlash = new float[8];
    private final boolean[] wasCooling = new boolean[8];

    private float animatedFrameIndex = 0f;
    private float animatedScale = 1f;
    private final long[] lastFrameNanos = new long[1];

    /** Прогрес появи хотбара (0 = знизу за екраном, 1 = на місці) — плавний в'їзд замість миттєвої появи. */
    private float entrance = 0f;

    /** Спалах рамки вибору (0..1) — коротке «пружне» збільшення одразу після зміни слота. */
    private float selectionPulse = 0f;

    /** Попередній вибраний слот — щоб озвучити й пульснути рівно на ЗМІНІ, а не щокадру. */
    private int lastSelectedIndex = -1;

    private static final float CATCH_UP_SPEED = 14f;
    private static final float ENTRANCE_SPEED = 8f;
    private static final float PULSE_DECAY_SPEED = 6f;

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
        // Плавний в'їзд знизу: поки entrance росте 0→1, хотбар піднімається
        // на місце (ease-out, тож гальмує в кінці, а не лінійно).
        int baseTop = screenHeight - MARGIN_BOTTOM - SLOT_SIZE;
        // Каскад: кожен слот в'їжджає з невеликою затримкою відносно попереднього
        // (easeOutBack — легкий «підскок» при осіданні).
        int top = baseTop + slotEntranceOffset(selectedIndex);

        long currentTick = minecraft.level != null ? minecraft.level.getGameTime() : 0L;

        // Прохід 1: базові слоти. Кожен слот малює лише СВІЙ статичний
        // (невибраний) вигляд — тонкий контур, прогрес готовності,
        // предмет. Рамка вибору — окремий шар (прохід 2), що плавно
        // ковзає між слотами, а не перемальовується як частина слота —
        // інакше вона "стрибала" б миттєво замість плавного ковзання.
        for (int index = 0; index < allowedSlots.size(); index++) {
            int inventorySlot = allowedSlots.get(index);
            int slotX = left + index * (SLOT_SIZE + SLOT_GAP);
            int slotY = baseTop + slotEntranceOffset(index);

            if (maniac && index == 0) {
                renderStrikeSlot(graphics, minecraft, slotX, slotY, inventorySlot, index + 1, currentTick);
            } else if (maniac) {
                SlotKind kind = classifyManiacSlot(index);
                renderManiacSlot(graphics, minecraft, slotX, slotY, inventorySlot, index + 1, kind, currentTick);
            } else {
                renderPlayerSlot(graphics, minecraft, slotX, slotY, inventorySlot, index + 1);
            }
        }

        // Прохід 2: ковзна рамка вибору поверх усього — колір залежить
        // від типу ЩОЙНО вибраного слота (маньяк: удар/здібність/пастка
        // кожен має власний акцент), звичайний виживий завжди білий.
        SlotKind selectedKind = maniac ? (selectedIndex == 0 ? SlotKind.STRIKE : classifyManiacSlot(selectedIndex))
                : SlotKind.STRIKE; // STRIKE тут лише позначає "нейтральний білий колір", виживий не має типів
        int selectionColor = SELECTION_COLOR;
        renderSelectionFrame(graphics, left, top, maniac && selectedIndex == 0, selectionColor);
    }

    /** Вертикальний зсув слота під час каскадної появи (0 — на місці). */
    private int slotEntranceOffset(int index) {
        float local = com.log_to_kot.maniacmod.client.style.UiAnim.clamp01(entrance * 1.35f - index * 0.10f);
        float e = com.log_to_kot.maniacmod.client.style.UiAnim.easeOutBack(local, 0.8f);
        return Math.round((1f - e) * (SLOT_SIZE + MARGIN_BOTTOM));
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
        // Пульс вибору — короткий «пружний» надлишок масштабу одразу після зміни слота.
        int size = Math.round(SLOT_SIZE * (animatedScale + selectionPulse * 0.12f));
        int drawX = Math.round(slotCenterX - size / 2f);
        int drawY = Math.round(slotCenterY - size / 2f);

        // М'яке «дихання» яскравості рамки вибору.
        float breathe = 0.82f + 0.18f * (float) Math.sin(System.currentTimeMillis() / 300.0);
        int frameColor = GeneratorFuelUiTheme.alpha(color, breathe);
        if (isStrikeSlot) {
            strokeCircle(graphics, Math.round(slotCenterX), Math.round(slotCenterY), size / 2 + 2,
                GeneratorFuelUiTheme.alpha(color, 0.22f), 1);
            strokeCircle(graphics, Math.round(slotCenterX), Math.round(slotCenterY), size / 2, frameColor, 2);
        } else {
            com.log_to_kot.maniacmod.client.style.UiFx.glow(graphics, drawX, drawY, size, size, color,
                0.30f + 0.35f * breathe + selectionPulse * 0.5f, 5);
            strokeSquare(graphics, drawX - 2, drawY - 2, size + 4, 1, GeneratorFuelUiTheme.alpha(color, 0.22f));
            strokeSquare(graphics, drawX, drawY, size, SELECTION_THICKNESS, frameColor);
            com.log_to_kot.maniacmod.client.style.UiFx.brackets(graphics, drawX, drawY, size, size,
                GeneratorFuelUiTheme.alpha(color, 0.95f), 7, 0.4f + 0.6f * selectionPulse, 5);
            if (selectionPulse > 0.03f) {
                GeneratorFuelUiTheme.pulseRing(graphics, drawX, drawY, size, size, color, 1f - selectionPulse, 10);
            }
        }
    }

    private void advanceAnimation(int targetIndex) {
        float dt = com.log_to_kot.maniacmod.client.style.UiAnim.frameDt(lastFrameNanos);
        frameDt = dt;
        for (int i = 0; i < readyFlash.length; i++) {
            readyFlash[i] = com.log_to_kot.maniacmod.client.style.UiAnim.approach(readyFlash[i], 0f, 4.5f, dt);
        }

        animatedFrameIndex = com.log_to_kot.maniacmod.client.style.UiAnim
                .approach(animatedFrameIndex, targetIndex, CATCH_UP_SPEED, dt);
        if (Math.abs(targetIndex - animatedFrameIndex) < 0.01f) animatedFrameIndex = targetIndex;

        animatedScale = com.log_to_kot.maniacmod.client.style.UiAnim
                .approach(animatedScale, SELECTED_SCALE, CATCH_UP_SPEED, dt);
        entrance = com.log_to_kot.maniacmod.client.style.UiAnim
                .approach(entrance, 1f, ENTRANCE_SPEED, dt);
        selectionPulse = com.log_to_kot.maniacmod.client.style.UiAnim
                .approach(selectionPulse, 0f, PULSE_DECAY_SPEED, dt);

        // Звук і пульс — рівно на ЗМІНІ вибраного слота (не щокадру).
        if (targetIndex != lastSelectedIndex) {
            if (lastSelectedIndex != -1) {
                selectionPulse = 1f;
            }
            lastSelectedIndex = targetIndex;
        }
    }

    // ── Виживий: звичайний слот ────────────────────────────────────────

    private void renderPlayerSlot(GuiGraphics graphics, Minecraft minecraft, int x, int y,
                                   int inventorySlot, int displayNumber) {
        GeneratorFuelUiTheme.drawSlot(graphics, x, y, SLOT_SIZE, SLOT_SIZE, SLOT_BORDER);
        drawSlotContents(graphics, minecraft, x, y, inventorySlot, displayNumber);
    }

    // ── Маньяк: слот здібності або пастки ─────────────────────────────

    private void renderManiacSlot(GuiGraphics graphics, Minecraft minecraft, int x, int y,
                                   int inventorySlot, int displayNumber, SlotKind kind, long currentTick) {
        int strip = kind == SlotKind.TRAP ? TRAP_STRIP : ABILITY_STRIP;
        int flashIdx = Math.min(displayNumber, readyFlash.length - 1);

        GeneratorFuelUiTheme.drawSlot(graphics, x, y, SLOT_SIZE, SLOT_SIZE, SLOT_BORDER);
        // Смужка типу слота зверху.
        graphics.fill(x + 1, y + 1, x + SLOT_SIZE - 1, y + 3, GeneratorFuelUiTheme.alpha(strip, 0.85f));

        float cooldownFraction = readCooldownFraction(kind, displayNumber, currentTick);
        boolean cooling = cooldownFraction > 0f;
        if (wasCooling[flashIdx] && !cooling) readyFlash[flashIdx] = 1f;
        wasCooling[flashIdx] = cooling;

        drawSlotContents(graphics, minecraft, x, y, inventorySlot, displayNumber);

        if (cooling) {
            // Штора кулдауну + нижній бар готовності в стилі генератора.
            int curtain = Math.round((SLOT_SIZE - 2) * cooldownFraction);
            graphics.fill(x + 1, y + 1, x + SLOT_SIZE - 1, y + 1 + curtain, 0xB0000000);
            float ready = clamp01(1f - cooldownFraction);
            GeneratorFuelUiTheme.drawBarFx(graphics, x + 5, y + SLOT_SIZE - 9, SLOT_SIZE - 10, 5,
                ready, ready, strip, 0f);
        }
        if (readyFlash[flashIdx] > 0.02f) {
            graphics.fill(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1,
                GeneratorFuelUiTheme.alpha(0xFFFFFFFF, readyFlash[flashIdx] * 0.5f));
            GeneratorFuelUiTheme.pulseRing(graphics, x, y, SLOT_SIZE, SLOT_SIZE, strip,
                1f - readyFlash[flashIdx], 9);
        }
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
                                   int inventorySlot, int displayNumber, long currentTick) {
        int centerX = x + SLOT_SIZE / 2;
        int centerY = y + SLOT_SIZE / 2;
        int radius = SLOT_SIZE / 2;

        fillCircle(graphics, centerX, centerY, radius, GeneratorFuelUiTheme.PANEL_BG);
        fillCircle(graphics, centerX, centerY, radius - 1, 0x66000000);
        strokeCircle(graphics, centerX, centerY, radius, STRIKE_BORDER, 1);

        // Кільце перезарядки удару: той самий канал (AbilityCooldownPacket
        // / ClientMatchState.abilityCooldownFraction), яким уже живляться
        // кільця здібностей і пасток — просто по колу, а не по периметру
        // квадрата, бо слот удару круглий.
        float cooldownFraction = ClientMatchState.abilityCooldownFraction(
            com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket.ATTACK_ID, currentTick);
        float readyFraction = clamp01(1f - cooldownFraction);
        boolean strikeCooling = cooldownFraction > 0f;
        if (wasCooling[0] && !strikeCooling) readyFlash[0] = 1f;
        wasCooling[0] = strikeCooling;
        if (readyFraction > 0f && readyFraction < 1f) {
            strokeProgressCircle(graphics, centerX, centerY, radius, STRIKE_GLYPH_COLOR, readyFraction, 2);
        }
        if (readyFlash[0] > 0.02f) {
            fillCircle(graphics, centerX, centerY, radius - 1, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, readyFlash[0] * 0.45f));
            strokeCircle(graphics, centerX, centerY, radius + Math.round((1f - readyFlash[0]) * 9f),
                GeneratorFuelUiTheme.alpha(STRIKE_GLYPH_COLOR, readyFlash[0]), 1);
        }

        drawStrikeGlyph(graphics, centerX, centerY, STRIKE_ICON_SIZE, STRIKE_GLYPH_COLOR);

        String number = String.valueOf(displayNumber);
        graphics.drawString(minecraft.font, number, x + 5, y + SLOT_SIZE - 11, SLOT_NUMBER_COLOR, true);

        renderPowerCharges(graphics, x + SLOT_SIZE / 2f, y + SLOT_SIZE / 2f, 1f);
    }

    /** Іконка удару — три подряпини кігтів (32×32, біла: тонується кольором готовності). */
    private static final ResourceLocation TEX_STRIKE_ICON =
        new ResourceLocation("maniacmod", "textures/gui/hotbar_maniac/maniac_strike_icon.png");

    private static void drawStrikeGlyph(GuiGraphics g, int centerX, int centerY, int iconSize, int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float gr = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        int x = centerX - iconSize / 2;
        int y = centerY - iconSize / 2;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(r, gr, b, 1.0f);
        g.blit(TEX_STRIKE_ICON, x, y, iconSize, iconSize, 0f, 0f, 32, 32, 32, 32);
        // Множник ОБОВ'ЯЗКОВО назад до білого, інакше решта GUI лишиться тонованою.
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
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
            strokeCircle(graphics, chargeCenterX, chargeCenterY, chargeRadius, GeneratorFuelUiTheme.PANEL_BORDER, 1);
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

    /**
     * Кільце прогресу (0..1) по колу, за годинниковою стрілкою від
     * верхньої точки (12 година) — той самий "напрямок читання", що й
     * {@link #strokeProgressSquare} (top -> right -> ...), лише по колу
     * замість периметра квадрата. {@code fraction} = 1 щойно вдарив
     * (кільце повне), 0 = перезарядка скінчилась (кільце зникає, бо
     * викликач малює лише поки {@code 0 < readyFraction < 1}).
     */
    private static void strokeProgressCircle(GuiGraphics g, int cx, int cy, int radius, int color,
                                              float fraction, int thickness) {
        float frac = clamp01(fraction);
        int steps = Math.max(24, radius * 2);
        int litSteps = Math.round(steps * frac);
        for (int t = 0; t < thickness; t++) {
            int r = radius - t;
            if (r <= 0) continue;
            for (int i = 0; i < litSteps; i++) {
                double angle = -Math.PI / 2 + 2 * Math.PI * i / steps;
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
