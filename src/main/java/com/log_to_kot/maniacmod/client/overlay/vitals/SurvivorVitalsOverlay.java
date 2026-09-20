package com.log_to_kot.maniacmod.client.overlay.vitals;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Постійний HUD зліва внизу за референсом: круглий медальйон із серцем,
 * справа від нього — дві шкали (хп зверху червоним, стаміна знизу синім),
 * під усім цим — іконки стану (поламана нога, повзання, непритомність).
 *
 * ── PNG-шари замість примітивів ───────────────────────────────────────
 * Уся панель — один спільний канвас {@link #PANEL_W}×{@link #PANEL_H},
 * кожен шар (фон, серце, кожна шкала) — окрема текстура ТОГО САМОГО
 * розміру канвасу, що malюється в ту саму позицію (0,0) відносно лівого
 * верхнього кута панелі — вирівнювання дає сам однаковий розмір текстур,
 * без ручного підбору зсувів під кожен шар.
 *
 * Виняток — серце: воно пульсує (росте/зменшується), тому для нього
 * рівний розмір канвасу зламав би пульсацію (довелось би або тягнути
 * піксель-арт, або губити чіткість). Тому heart_icon.png має власний
 * менший канвас {@link #HEART_ICON_SIZE}×{@link #HEART_ICON_SIZE} і
 * позиціюється/масштабується окремо навколо центру медальйона.
 *
 * Шкали — суцільна текстура-смужка (НЕ per-segment blit): втрата
 * хп/стаміни показується одним crop зліва-направо по ширині
 * {@code graphics.blit} з обмеженим regionWidth замість повної ширини
 * джерела. Під "порожньою" відрізаною частиною лежить bar_empty
 * (тьмяна версія тієї ж смужки) — тому відсутність hp виглядає як
 * тьмяні сегменти, а не голий фон панелі.
 *
 * ── Текстури-заповнювачі ────────────────────────────────────────────
 * Усі файли нижче — плейсхолдери (розміри зафіксовані остаточно, вміст
 * замінити 1:1 на фінальний арт того самого розміру):
 *   vitals_panel_bg.png     {@link #PANEL_W}×{@link #PANEL_H}
 *   heart_icon.png          {@link #HEART_ICON_SIZE}×{@link #HEART_ICON_SIZE}
 *   hp_bar_full.png / _empty.png         {@link #BAR_INNER_W}×{@link #BAR_INNER_H}
 *   stamina_bar_full.png / _empty.png    {@link #BAR_INNER_W}×{@link #BAR_INNER_H}
 *   status_icon_broken_leg/crawling/unconscious.png   {@link #ICON_SIZE}×{@link #ICON_SIZE} (ще не намальовані — плейсхолдер-рамка лишається для них)
 */
@OnlyIn(Dist.CLIENT)
public final class SurvivorVitalsOverlay {

    private SurvivorVitalsOverlay() {}

    // ── Спільний канвас панелі (медальйон + обидві шкали одним шаром) ────
    private static final int PANEL_W = 220;
    private static final int PANEL_H = 48;

    private static final ResourceLocation TEX_PANEL_BG =
        new ResourceLocation("maniacmod", "textures/gui/vitals/vitals_panel_bg.png");

    // ── Серце: власний менший канвас, бо пульсує (росте/зменшується) ─────
    private static final int HEART_ICON_SIZE = 32;
    private static final ResourceLocation TEX_HEART =
        new ResourceLocation("maniacmod", "textures/gui/vitals/heart_icon.png");

    // Геометрія медальйона всередині PANEL-канвасу (мусить збігатись із
    // тим, як намальований vitals_panel_bg.png — коло центроване тут).
    private static final int MEDALLION_CX = 23;
    private static final int MEDALLION_CY = 24;
    private static final int HEART_BASE_DRAW_SIZE = 26; // видимий розмір серця в спокої (менше за HEART_ICON_SIZE-канвас, щоб пульсація мала запас рости всередину канвасу)

    // ── Шкали: суцільна текстура-смужка, обрізається зліва-направо ───────
    private static final int BAR_LEFT = 54;   // зсув вікна шкали від лівого краю панелі
    private static final int BAR_FRAME_W = 150;
    private static final int BAR_FRAME_H = 16;
    private static final int BAR_FRAME_THICKNESS = 2;
    private static final int BAR_INNER_W = BAR_FRAME_W - BAR_FRAME_THICKNESS * 2; // 146 — точний розмір hp_bar_full.png/stamina_bar_full.png
    private static final int BAR_INNER_H = BAR_FRAME_H - BAR_FRAME_THICKNESS * 2; // 12
    private static final int HP_TOP = 2;
    private static final int STA_TOP = 24;

    private static final ResourceLocation TEX_HP_FULL =
        new ResourceLocation("maniacmod", "textures/gui/vitals/hp_bar_full.png");
    private static final ResourceLocation TEX_HP_EMPTY =
        new ResourceLocation("maniacmod", "textures/gui/vitals/hp_bar_empty.png");
    private static final ResourceLocation TEX_STAMINA_FULL =
        new ResourceLocation("maniacmod", "textures/gui/vitals/stamina_bar_full.png");
    private static final ResourceLocation TEX_STAMINA_EMPTY =
        new ResourceLocation("maniacmod", "textures/gui/vitals/stamina_bar_empty.png");

    // ── Іконки стану (ще плейсхолдер-рамка — PNG для них поки не намальовані) ──
    private static final int ICON_SIZE = 16;
    private static final int ICONS_GAP = 4;

    private static final ResourceLocation ICON_BROKEN_LEG =
        new ResourceLocation("maniacmod", "textures/gui/vitals/status_icon_broken_leg.png");
    private static final ResourceLocation ICON_CRAWLING =
        new ResourceLocation("maniacmod", "textures/gui/vitals/status_icon_crawling.png");
    private static final ResourceLocation ICON_UNCONSCIOUS =
        new ResourceLocation("maniacmod", "textures/gui/vitals/status_icon_unconscious.png");

    public static void render(GuiGraphics graphics) {
        if (!ClientMatchState.isSurvivor()) return;
        // Видимість — за дозволом фази HUD, а не за "чи фаза ігрова":
        // ROLE_REVEAL технічна, але сервер уже надіслав ролі й перший
        // знімок vitals (SurvivorModule.onPhaseEnter), і гравець має
        // бачити свої показники з першого кадру гри, а не з моменту,
        // коли роль-екран згасне.
        //
        // LOBBY — виняток: /maniac morph survivor навмисно призначає
        // роль виживого посеред лобі саме для дебаг-тестування цього
        // HUD (у LOBBY немає PhaseRule.HUD).
        boolean hudPhase = ClientMatchState.allows(com.log_to_kot.maniacmod.core.phase.PhaseRule.HUD)
            || ClientMatchState.phase() == com.log_to_kot.maniacmod.core.phase.GamePhase.LOBBY;
        if (!hudPhase) return;

        Minecraft mc = Minecraft.getInstance();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int hp = ClientMatchState.hp();
        int maxHp = ClientMatchState.maxHp();
        if (maxHp <= 0) return; // vitals ще не прийшли жодного разу цього матчу

        SurvivorState state = ClientMatchState.survivorState();
        boolean hasIcons = state != SurvivorState.HEALTHY;

        int panelLeft = 10;
        int panelTop = screenHeight - 10 - PANEL_H;

        // 1) Фон панелі — один шар на весь канвас.
        blitFull(graphics, TEX_PANEL_BG, panelLeft, panelTop, PANEL_W, PANEL_H, PANEL_W, PANEL_H);

        // 2) Серце — окремий менший канвас, центрований на медальйоні, пульсує.
        renderHeart(graphics, panelLeft + MEDALLION_CX, panelTop + MEDALLION_CY, ClientMatchState.heartbeat());

        // 3) Шкали HP і стаміни — суцільна смужка, crop зліва-направо.
        float hpFraction = maxHp <= 0 ? 0f : clamp01((float) hp / maxHp);
        float staminaFraction = clamp01(ClientMatchState.stamina());

        renderCroppedBar(graphics, panelLeft + BAR_LEFT + BAR_FRAME_THICKNESS, panelTop + HP_TOP + BAR_FRAME_THICKNESS,
            TEX_HP_FULL, TEX_HP_EMPTY, hpFraction);

        // Поламана нога = стаміна 0 до лікування. Сервер уже шле 0 у
        // цьому стані (SurvivorModule.sendVitals), але клієнт не
        // покладається на це сліпо: правило дизайну має діяти навіть
        // якщо між двома пакетами шкала встигла б показати старе
        // значення.
        boolean staminaLocked = state == SurvivorState.BROKEN_LEG;
        renderCroppedBar(graphics, panelLeft + BAR_LEFT + BAR_FRAME_THICKNESS, panelTop + STA_TOP + BAR_FRAME_THICKNESS,
            TEX_STAMINA_FULL, TEX_STAMINA_EMPTY, staminaLocked ? 0f : staminaFraction);

        if (hasIcons) {
            int iconsTop = panelTop + STA_TOP + BAR_FRAME_H + ICONS_GAP;
            int iconsLeft = panelLeft + BAR_LEFT;
            renderStatusIcon(graphics, mc, iconsLeft, iconsTop, state);
        }

        if (state == SurvivorState.CRAWLING) {
            renderStandUpBar(graphics, mc);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ── Серце: пульсуюча текстура на власному канвасі, центрована на медальйоні ──

    /**
     * "Стукає" через короткий масштабний імпульс, що повторюється
     * швидше при вищому heartbeat (0 = спокійне, 1 = впритул до
     * маньяка). Період удару: 900 мс на далекій межі до 260 мс впритул.
     * Замість перефарбовування текстури (немає per-pixel tint у
     * простому blit без шейдера) — пульсація реалізована лише зміною
     * РОЗМІРУ відмальовки (сама текстура вже намальована "гарячим"
     * кольором, тьмяного стану серця без heartbeat тут немає — коли
     * з'явиться повний арт, можна додати heart_icon_calm.png і
     * перемикати текстуру за heartbeat > 0, а не лише розмір).
     */
    private static void renderHeart(GuiGraphics graphics, int centerX, int centerY, float heartbeat) {
        int size = HEART_BASE_DRAW_SIZE;

        if (heartbeat > 0f) {
            long periodMs = Math.round(900 - 640 * heartbeat);
            long now = System.currentTimeMillis();
            float phase = (now % periodMs) / (float) periodMs;
            float pulse = phase < 0.18f
                ? phase / 0.18f
                : Math.max(0f, 1f - (phase - 0.18f) / 0.82f);

            size = Math.round(HEART_BASE_DRAW_SIZE + pulse * HEART_BASE_DRAW_SIZE * 0.25f * heartbeat);
        }

        int drawX = centerX - size / 2;
        int drawY = centerY - size / 2;
        // Тут НЕ 1:1 — draw-розмір (size) пульсує, а джерело завжди
        // читається повністю (HEART_ICON_SIZE×HEART_ICON_SIZE) —
        // справжнє масштабування, тому виклик напряму, не через blitFull.
        graphics.blit(TEX_HEART, drawX, drawY, size, size, 0f, 0f,
            HEART_ICON_SIZE, HEART_ICON_SIZE, HEART_ICON_SIZE, HEART_ICON_SIZE);
    }

    // ── Шкали: суцільна смужка, обрізана зліва-направо ────────────────────

    /**
     * Малює "порожню" версію смужки на всю ширину (тьмяний фон
     * сегментів), а поверх неї — "повну" версію, ОБРІЗАНУ по ширині
     * visibleWidth = ширина * fraction: показана частка лишається
     * ПРИТУЛЕНОЮ ДО ЛІВОГО краю смуги (джерело читається з (0,0),
     * draw-позиція теж від лівого краю (x,y)), решта справа — тьмяний
     * empty-шар знизу. Тому втрата "з'їдає" смугу справа, а не зліва —
     * це і є "прогрес зменшується зліва направо", як у ванільного хп.
     */
    private static void renderCroppedBar(GuiGraphics graphics, int x, int y,
                                          ResourceLocation full, ResourceLocation empty, float fraction) {
        blitFull(graphics, empty, x, y, BAR_INNER_W, BAR_INNER_H, BAR_INNER_W, BAR_INNER_H);

        int visibleWidth = Math.round(BAR_INNER_W * fraction);
        if (visibleWidth <= 0) return;
        // Обрізаний blit: малюємо лише ЛІВУ частку джерела шириною
        // visibleWidth — і джерело (u=0), і draw-позиція (x) лишаються
        // від лівого краю, тому права (обрізана) частина не читається
        // і не малюється, а видима частка лишається впритул до
        // лівого краю смуги.
        graphics.blit(full, x, y, visibleWidth, BAR_INNER_H,
            0f, 0f, visibleWidth, BAR_INNER_H, BAR_INNER_W, BAR_INNER_H);
    }

    /** Blit 1:1 (draw size == source region size) через 9-арг. overload з явним розміром текстури. */
    private static void blitFull(GuiGraphics graphics, ResourceLocation tex, int x, int y,
                                  int drawWidth, int drawHeight, int textureWidth, int textureHeight) {
        graphics.blit(tex, x, y, drawWidth, drawHeight, 0f, 0f, drawWidth, drawHeight, textureWidth, textureHeight);
    }

    // ── Іконка стану (плейсхолдер — файли ICON_* ще не намальовані) ──────

    private static void renderStatusIcon(GuiGraphics graphics, Minecraft mc, int x, int y, SurvivorState state) {
        ResourceLocation icon = switch (state) {
            case BROKEN_LEG -> ICON_BROKEN_LEG;
            case CRAWLING -> ICON_CRAWLING;
            case UNCONSCIOUS -> ICON_UNCONSCIOUS;
            case HEALTHY, ELIMINATED, ESCAPED -> null; // гілки не досягаються (guard у render()), але switch має бути вичерпним
        };
        if (icon == null) return;
        blitFull(graphics, icon, x, y, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
    }

    // ── Шкала вставання під час CRAWLING ──────────────────────────────────

    private static final int STAND_UP_PANEL_WIDTH = 260;
    private static final int STAND_UP_PANEL_HEIGHT = 58;
    private static final int STAND_UP_PANEL_MARGIN = 14;
    private static final int STAND_UP_BAR_HEIGHT = 14;
    /** Відступ панелі від центру екрана вниз — нижче приціла, щоб не заважала. */
    private static final int STAND_UP_OFFSET_FROM_CENTER = 40;

    /**
     * Панель "ТИСНИ ПРОБІЛ, ЩОБ ВСТАТИ" з прогрес-баром і лічильником
     * n/N. Раніше тут був лише напис без шкали, а сервер рахував
     * натискання втемну — гравець тиснув пробіл і не бачив, чи це взагалі
     * щось дає.
     *
     * Стилі — ті самі, що в {@code GeneratorProgressOverlay}
     * (ManiacUiTheme): панель, роздільник, прямокутний бар. Дані —
     * лише з {@code ClientMatchState} (StandUpProgressPacket); шкала
     * нічого не рахує сама.
     */
    private static void renderStandUpBar(GuiGraphics graphics, Minecraft mc) {
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int required = ClientMatchState.standUpRequired();
        int presses = ClientMatchState.standUpPresses();

        int panelX = screenW / 2 - STAND_UP_PANEL_WIDTH / 2;
        int panelY = screenH / 2 + STAND_UP_OFFSET_FROM_CENTER;

        graphics.fill(panelX, panelY, panelX + STAND_UP_PANEL_WIDTH, panelY + STAND_UP_PANEL_HEIGHT,
            ManiacUiTheme.PANEL_BG);
        ManiacUiTheme.border1px(graphics, panelX, panelY, STAND_UP_PANEL_WIDTH, STAND_UP_PANEL_HEIGHT,
            ManiacUiTheme.BORDER);

        Component title = Component.translatable("maniacmod.hud.stand_up_prompt");
        int titleY = panelY + STAND_UP_PANEL_MARGIN - 4;
        graphics.drawCenteredString(mc.font, title, screenW / 2, titleY, ManiacUiTheme.TEXT_TITLE);
        ManiacUiTheme.divider(graphics, panelX + STAND_UP_PANEL_MARGIN,
            titleY + mc.font.lineHeight + 4, STAND_UP_PANEL_WIDTH - STAND_UP_PANEL_MARGIN * 2);

        int barX = panelX + STAND_UP_PANEL_MARGIN;
        int barY = panelY + STAND_UP_PANEL_HEIGHT - STAND_UP_BAR_HEIGHT - STAND_UP_PANEL_MARGIN + 2;
        int barW = STAND_UP_PANEL_WIDTH - STAND_UP_PANEL_MARGIN * 2;

        // required == 0 — пакет прогресу ще не дійшов (перший кадр
        // після падіння): малюємо порожню шкалу, а не ділимо на нуль.
        float fraction = required > 0 ? clamp01((float) presses / required) : 0f;
        ManiacUiTheme.drawProgressBar(graphics, barX, barY, barW, STAND_UP_BAR_HEIGHT, fraction,
            ManiacUiTheme.BAR_FILL_WARN);

        if (required > 0) {
            Component counter = Component.translatable("maniacmod.hud.stand_up_progress", presses, required);
            graphics.drawCenteredString(mc.font, counter, screenW / 2,
                barY + (STAND_UP_BAR_HEIGHT - mc.font.lineHeight) / 2, ManiacUiTheme.TEXT_BODY);
        }
    }
}
