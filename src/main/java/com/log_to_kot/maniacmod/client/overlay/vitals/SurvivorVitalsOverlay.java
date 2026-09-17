package com.log_to_kot.maniacmod.client.overlay.vitals;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Постійний HUD зліва внизу за референсом: круглий медальйон із
 * серцем (частішає й "стукає" ближче до маньяка), справа від нього —
 * дві сегментовані шкали-"камінці" (хп зверху червоним, стаміна знизу
 * синім), під усім цим — іконки стану (поламана нога, повзання,
 * непритомність).
 *
 * Малює лише те, що вже прийшло в ClientMatchState через
 * SurvivorVitalsPacket — сам нічого не рахує (див. vitals/README.md).
 * Видимий тільки виживому в ігровій фазі.
 *
 * ── Сегментований бар замість суцільного ──────────────────────────────
 * Референс показує хп/стаміну як ряд окремих ромбовидних камінців, не
 * один суцільний прямокутник, що заповнюється. SEGMENT_COUNT камінців
 * на шкалу — кожен закривається/відкривається цілим камінцем, а не
 * частковим заповненням (як здоров'я в іграх з "серцями": камінець
 * або горить, або ні). Дробову частку останнього часткового камінця
 * малюємо приглушенням яскравості, не обрізанням форми — обрізати
 * ромб по вертикалі дало б прямокутний зріз, що зламало б форму
 * камінця.
 *
 * ── Іконки станів: тимчасові плейсхолдери ────────────────────────────
 * PNG-текстур для статус-іконок ще немає в проєкті (як і для
 * медальйону/камінців — усе тут примітиви GuiGraphics). Шляхи ICON_*
 * зафіксовані заздалегідь; заміна на blit(...) — коли з'являться файли.
 */
@OnlyIn(Dist.CLIENT)
public final class SurvivorVitalsOverlay {

    private SurvivorVitalsOverlay() {}

    // ── Геометрія медальйону ────────────────────────────────────────────
    private static final int MARGIN_X = 10;
    private static final int MARGIN_BOTTOM = 10;
    private static final int MEDALLION_DIAMETER = 46;
    private static final int MEDALLION_RING_THICKNESS = 3;
    private static final int MEDALLION_RING_COLOR = 0xFFB9B9B9; // той самий сірий, що рамка слотів хотбару
    private static final int MEDALLION_BG_COLOR = 0xE6140F12;

    // ── Геометрія шкал ───────────────────────────────────────────────────
    private static final int BAR_GAP_FROM_MEDALLION = 8;
    private static final int BAR_HEIGHT = 18;
    private static final int BAR_ROW_GAP = 4;
    private static final int SEGMENT_COUNT = 10;
    private static final int SEGMENT_GAP = 2;
    private static final int BAR_FRAME_THICKNESS = 2;
    private static final int BAR_FRAME_COLOR = 0xFFB9B9B9;
    private static final int BAR_BG_COLOR = 0xCC1B1E22;      // сталево-темний фон невикористаної частини
    private static final int HP_SEGMENT_COLOR = 0xFFB0242F;   // насичений червоний, як у референсі
    private static final int HP_SEGMENT_EMPTY_COLOR = 0xFF3A2226;
    private static final int STAMINA_SEGMENT_COLOR = 0xFF2E86D8; // насичений синій
    private static final int STAMINA_SEGMENT_EMPTY_COLOR = 0xFF25384A;
    private static final int STAMINA_SEGMENT_LOCKED_COLOR = 0xFF4A4A4A; // BROKEN_LEG — приглушений сірий

    // ── Іконки стану ──────────────────────────────────────────────────────
    private static final int ICON_SIZE = 16;

    private static final ResourceLocation ICON_BROKEN_LEG =
        new ResourceLocation("maniacmod", "textures/gui/vitals/broken_leg.png");
    private static final ResourceLocation ICON_CRAWLING =
        new ResourceLocation("maniacmod", "textures/gui/vitals/crawling.png");
    private static final ResourceLocation ICON_UNCONSCIOUS =
        new ResourceLocation("maniacmod", "textures/gui/vitals/unconscious.png");

    public static void render(GuiGraphics graphics) {
        if (!ClientMatchState.isSurvivor()) return;
        if (!ClientMatchState.isGameplay()) return;

        Minecraft mc = Minecraft.getInstance();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int hp = ClientMatchState.hp();
        int maxHp = ClientMatchState.maxHp();
        if (maxHp <= 0) return; // vitals ще не прийшли жодного разу цього матчу

        SurvivorState state = ClientMatchState.survivorState();
        boolean hasIcons = state != SurvivorState.HEALTHY;

        int blockHeight = BAR_HEIGHT * 2 + BAR_ROW_GAP;
        int medallionTop = screenHeight - MARGIN_BOTTOM - (blockHeight + MEDALLION_DIAMETER) / 2;
        int medallionLeft = MARGIN_X;

        int barsLeft = medallionLeft + MEDALLION_DIAMETER + BAR_GAP_FROM_MEDALLION;
        int hpTop = screenHeight - MARGIN_BOTTOM - blockHeight;
        int staminaTop = hpTop + BAR_HEIGHT + BAR_ROW_GAP;

        int barWidth = 150;

        renderMedallion(graphics, medallionLeft, medallionTop, ClientMatchState.heartbeat());
        renderSegmentedBar(graphics, barsLeft, hpTop, barWidth, hp, maxHp,
            HP_SEGMENT_COLOR, HP_SEGMENT_EMPTY_COLOR);
        renderStaminaBar(graphics, barsLeft, staminaTop, barWidth, state);

        if (hasIcons) {
            int iconsTop = staminaTop + BAR_HEIGHT + BAR_ROW_GAP + 2;
            renderStatusIcons(graphics, mc, barsLeft, iconsTop, state);
        }
    }

    // ── Медальйон із серцем ──────────────────────────────────────────────

    /**
     * Коло апроксимоване горизонтальними смугами змінної ширини
     * (пікселізоване коло — той самий підхід, що дає low-res
     * растеризація в референсі, а не гладке OpenGL-коло без текстури).
     */
    private static void renderMedallion(GuiGraphics graphics, int left, int top, float heartbeat) {
        int diameter = MEDALLION_DIAMETER;
        drawPixelCircle(graphics, left, top, diameter, MEDALLION_RING_COLOR);
        int innerInset = MEDALLION_RING_THICKNESS;
        drawPixelCircle(graphics, left + innerInset, top + innerInset,
            diameter - innerInset * 2, MEDALLION_BG_COLOR);

        int heartSize = diameter / 2;
        int heartX = left + (diameter - heartSize) / 2;
        int heartY = top + (diameter - heartSize) / 2;
        renderHeart(graphics, heartX, heartY, heartSize, heartbeat);
    }

    /** Заповнене коло діаметром d, намальоване рядками (проста растеризація кола без текстури). */
    private static void drawPixelCircle(GuiGraphics graphics, int left, int top, int d, int color) {
        if (d <= 0) return;
        float radius = d / 2f;
        float centerX = radius;
        float centerY = radius;
        for (int row = 0; row < d; row++) {
            float dy = row + 0.5f - centerY;
            float dxMax = (float) Math.sqrt(Math.max(0, radius * radius - dy * dy));
            int rowLeft = Math.round(centerX - dxMax);
            int rowRight = Math.round(centerX + dxMax);
            if (rowRight <= rowLeft) continue;
            graphics.fill(left + rowLeft, top + row, left + rowRight, top + row + 1, color);
        }
    }

    /**
     * "Стукає" через короткий масштабний імпульс, що повторюється
     * швидше при вищому heartbeat (0 = не видно взагалі, 1 = впритул
     * до маньяка). Період удару: 900 мс на далекій межі до 260 мс
     * впритул — та сама крива відчуття "серце заходиться", що
     * SurvivorModule.computeHeartbeat уже застосовує до відстані.
     */
    private static void renderHeart(GuiGraphics graphics, int x, int y, int baseSize, float heartbeat) {
        int size = baseSize;
        int colour = 0xFF7A1620; // тьмяно-бордовий у спокої — серце завжди видно на медальйоні, не лише біля маньяка

        if (heartbeat > 0f) {
            long periodMs = Math.round(900 - 640 * heartbeat);
            long now = System.currentTimeMillis();
            float phase = (now % periodMs) / (float) periodMs;
            float pulse = phase < 0.18f
                ? phase / 0.18f
                : Math.max(0f, 1f - (phase - 0.18f) / 0.82f);

            size = Math.round(baseSize + pulse * baseSize * 0.25f * heartbeat);
            int base = 0xFFB71C1C;
            int flash = 0xFFFF5252;
            colour = blend(base, flash, pulse * heartbeat);
        }

        int offset = (size - baseSize) / 2;
        drawHeartShape(graphics, x - offset, y - offset, size, colour);
    }

    /** Просте "серце" з двох квадратів-піввкіл і трикутного низу — без текстури. */
    private static void drawHeartShape(GuiGraphics graphics, int x, int y, int size, int colour) {
        int half = size / 2;
        int quarter = Math.max(1, size / 4);

        graphics.fill(x, y, x + half, y + half, colour);
        graphics.fill(x + half, y, x + size, y + half, colour);
        graphics.fill(x + quarter / 2, y + half, x + size - quarter / 2, y + half + quarter, colour);
        graphics.fill(x + quarter, y + half + quarter, x + size - quarter, y + half + quarter * 2, colour);
    }

    private static int blend(int colourA, int colourB, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aA = (colourA >> 24) & 0xFF, rA = (colourA >> 16) & 0xFF, gA = (colourA >> 8) & 0xFF, bA = colourA & 0xFF;
        int aB = (colourB >> 24) & 0xFF, rB = (colourB >> 16) & 0xFF, gB = (colourB >> 8) & 0xFF, bB = colourB & 0xFF;
        int a = Math.round(aA + (aB - aA) * t);
        int r = Math.round(rA + (rB - rA) * t);
        int g = Math.round(gA + (gB - gA) * t);
        int b = Math.round(bA + (bB - bA) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ── Сегментовані шкали ───────────────────────────────────────────────

    private static void renderSegmentedBar(GuiGraphics graphics, int left, int top, int width,
                                            int value, int max, int filledColor, int emptyColor) {
        drawBarFrame(graphics, left, top, width);
        float fraction = max <= 0 ? 0f : Math.max(0f, Math.min(1f, (float) value / max));
        drawSegments(graphics, left + BAR_FRAME_THICKNESS, top + BAR_FRAME_THICKNESS,
            width - BAR_FRAME_THICKNESS * 2, BAR_HEIGHT - BAR_FRAME_THICKNESS * 2,
            fraction, filledColor, emptyColor);
    }

    private static void renderStaminaBar(GuiGraphics graphics, int left, int top, int width, SurvivorState state) {
        drawBarFrame(graphics, left, top, width);
        float stamina = Math.max(0f, Math.min(1f, ClientMatchState.stamina()));

        // Поламана нога: стаміна замкнена на нулі сервером, тож
        // fraction тут і так вийде 0 — але колір "порожнього" сегмента
        // теж приглушується окремо, щоб було видно РІЗНИЦЮ між "щойно
        // вибігав усю стаміну" (звичайний порожній синій) і "стаміни
        // не буде, доки не полагодиш ногу" (сірий, неначе шкала вимкнена).
        int emptyColor = state == SurvivorState.BROKEN_LEG
            ? STAMINA_SEGMENT_LOCKED_COLOR : STAMINA_SEGMENT_EMPTY_COLOR;

        drawSegments(graphics, left + BAR_FRAME_THICKNESS, top + BAR_FRAME_THICKNESS,
            width - BAR_FRAME_THICKNESS * 2, BAR_HEIGHT - BAR_FRAME_THICKNESS * 2,
            stamina, STAMINA_SEGMENT_COLOR, emptyColor);
    }

    /** Рамка навколо шкали — той самий сірий контур, що на медальйоні й хотбарі, без скосу (пряма капсула). */
    private static void drawBarFrame(GuiGraphics graphics, int left, int top, int width) {
        graphics.fill(left, top, left + width, top + BAR_HEIGHT, BAR_FRAME_COLOR);
        graphics.fill(left + BAR_FRAME_THICKNESS, top + BAR_FRAME_THICKNESS,
            left + width - BAR_FRAME_THICKNESS, top + BAR_HEIGHT - BAR_FRAME_THICKNESS, BAR_BG_COLOR);
    }

    /**
     * SEGMENT_COUNT ромбовидних камінців у ряд. Кожен камінець або
     * повністю "горить" (filledColor), або порожній (emptyColor) —
     * дробова частка (гравець втратив половину останнього сегмента
     * хп) позначається проміжним відтінком між ними, а не частковим
     * заповненням форми.
     */
    private static void drawSegments(GuiGraphics graphics, int left, int top, int width, int height,
                                      float fraction, int filledColor, int emptyColor) {
        int segmentWidth = (width - SEGMENT_GAP * (SEGMENT_COUNT - 1)) / SEGMENT_COUNT;
        if (segmentWidth <= 0) return;

        float exactFilled = fraction * SEGMENT_COUNT;
        int fullSegments = (int) Math.floor(exactFilled);
        float partial = exactFilled - fullSegments;

        for (int i = 0; i < SEGMENT_COUNT; i++) {
            int segX = left + i * (segmentWidth + SEGMENT_GAP);
            int color;
            if (i < fullSegments) {
                color = filledColor;
            } else if (i == fullSegments && partial > 0.05f) {
                color = blend(emptyColor, filledColor, partial);
            } else {
                color = emptyColor;
            }
            drawDiamondSegment(graphics, segX, top, segmentWidth, height, color);
        }
    }

    /**
     * Один камінець — прямокутник зі скошеними кутами (той самий
     * "сходинками" прийом, що на слотах хотбару, тепер з усіх чотирьох
     * кутів одразу), а не ідеальний ромб: при вузькій ширині (менше за
     * висоту) чистий ромб виродився б у трикутники, тоді як
     * прямокутник зі скосами лишається впізнаваним "камінцем" за
     * будь-якого співвідношення сторін.
     */
    private static void drawDiamondSegment(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        int cut = Math.max(1, Math.min(w, h) / 3);
        graphics.fill(x, y + cut, x + w, y + h - cut, color);           // середня смуга на всю ширину
        int steps = Math.max(1, cut);
        for (int i = 0; i < steps; i++) {
            int inset = cut - i;
            if (inset <= 0) break;
            // Верхній і нижній край звужуються симетрично з обох боків.
            graphics.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            graphics.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    // ── Іконки стану ─────────────────────────────────────────────────────

    private static void renderStatusIcons(GuiGraphics graphics, Minecraft mc, int left, int top, SurvivorState state) {
        switch (state) {
            case BROKEN_LEG -> drawIconPlaceholder(graphics, mc, left, top, ICON_BROKEN_LEG, "К", 0xFF8D6E63);
            case CRAWLING -> drawIconPlaceholder(graphics, mc, left, top, ICON_CRAWLING, "П", 0xFFBF360C);
            case UNCONSCIOUS -> drawIconPlaceholder(graphics, mc, left, top, ICON_UNCONSCIOUS, "!", 0xFF37474F);
            case HEALTHY -> { /* без іконок — гілка не досягається (guard у render()) */ }
        }
    }

    /**
     * Рамка-плейсхолдер з однією літерою замість PNG. За шляхом
     * {@code res} нічого зараз не лежить — параметр прийнятий і
     * названий тут навмисно, щоб сигнатура вже відповідала майбутньому
     * {@code graphics.blit(res, x, y, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE)}.
     */
    private static void drawIconPlaceholder(GuiGraphics graphics, Minecraft mc, int x, int y,
                                             ResourceLocation res, String glyph, int colour) {
        graphics.fill(x, y, x + ICON_SIZE, y + ICON_SIZE, 0xCC000000);
        graphics.fill(x + 1, y + 1, x + ICON_SIZE - 1, y + ICON_SIZE - 1, colour);
        int textX = x + ICON_SIZE / 2 - mc.font.width(glyph) / 2;
        int textY = y + ICON_SIZE / 2 - 4;
        graphics.drawString(mc.font, glyph, textX, textY, 0xFFFFFFFF, true);
    }
}
