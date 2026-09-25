package com.log_to_kot.maniacmod.client.overlay.vitals;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.log_to_kot.maniacmod.client.ManiacKeybinds;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Постійний HUD зліва внизу за референсом: круглий медальйон із серцем,
 * справа від нього — дві шкали (хп зверху червоним, стаміна знизу
 * синім), НАД усім цим (над панеллю, над HP) — іконки стану (поламана
 * нога, повзання, непритомність): раніше стояли під стаміною, знизу,
 * де їх легко пропустити; тепер — над HP, де гравець побачить їх
 * першими.
 *
 * ── PNG-шари замість примітивів ───────────────────────────────────────
 * Уся панель — один спільний канвас {@link #PANEL_W}×{@link #PANEL_H},
 * кожен шар (фон, серце, кожна шкала, декоративний оверлей) — окрема
 * текстура ТОГО САМОГО розміру канвасу, що malюється в ту саму позицію
 * (0,0) відносно лівого верхнього кута панелі — вирівнювання дає сам
 * однаковий розмір текстур, без ручного підбору зсувів під кожен шар.
 *
 * Порядок шарів (знизу вгору): фон → серце → HP-шкала → стамінa-шкала
 * → ДЕКОРАТИВНИЙ ОВЕРЛЕЙ (рамка/скло, {@link #TEX_PANEL_OVERLAY}).
 * Оверлей малюється ОСТАННІМ і лягає поверх усього — це "скло" чи
 * рамка, що закриває решту шарів (наприклад, медальйон серця виглядає,
 * ніби він під випуклим склом), а не декор поруч із ними.
 *
 * Іконка стану (поламана нога/повзання/непритомність) — НАД усією
 * панеллю (над HP), а не під стаміною: це те, що гравець має помітити
 * першим.
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
 *   vitals_panel_bg.png       {@link #PANEL_W}×{@link #PANEL_H}
 *   vitals_panel_overlay.png  {@link #PANEL_W}×{@link #PANEL_H} (декоративний шар — рамка/скло, малюється останнім)
 *   heart_icon.png            {@link #HEART_ICON_SIZE}×{@link #HEART_ICON_SIZE}
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

    // ── Декоративний шар поверх усього (рамка/скло) ───────────────────────
    // Малюється ОСТАННІМ, поверх фону, серця й обох шкал — той самий
    // канвас PANEL_W×PANEL_H, та сама позиція (panelLeft, panelTop), що
    // й фон: візуальне "скло", що ЗАКРИВАЄ решту шарів (наприклад,
    // серце під випуклим склом медальйона), а не декор поруч із ними.
    // Прозорі пікселі текстури лишають нижні шари видимими як завжди;
    // рудиментарний плейсхолдер тут, як і в status_icon_*, — суцільно
    // прозорий PNG, поки немає фінального арту.
    private static final ResourceLocation TEX_PANEL_OVERLAY =
        new ResourceLocation("maniacmod", "textures/gui/vitals/vitals_panel_overlay.png");

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

        // 4) Декоративний шар поверх усього (рамка/скло) — той самий
        // розмір і позиція, що фон (п. 1), малюється ОСТАННІМ із усіх
        // шарів усередині канвасу панелі, щоб лягти поверх серця й
        // обох шкал (не лише фону).
        blitFull(graphics, TEX_PANEL_OVERLAY, panelLeft, panelTop, PANEL_W, PANEL_H, PANEL_W, PANEL_H);

        if (hasIcons) {
            // Над HP (над усією панеллю), а не під стаміною: іконка стану —
            // те, що гравець має помітити першим, тому вона йде НАД
            // медальйоном/шкалами, а не похована знизу під ними.
            int iconsTop = panelTop - ICONS_GAP - ICON_SIZE;
            int iconsLeft = panelLeft + BAR_LEFT;
            renderStatusIcon(graphics, mc, iconsLeft, iconsTop, state);
        }

        renderHighlightSlot(graphics, mc, panelLeft, panelTop);

        if (state == SurvivorState.CRAWLING) {
            renderStandUpBar(graphics, mc);
        }
    }

    // ── Сила підсвітки генераторів (клавіша 5) ───────────────────────────

    private static final int HL_SLOT = 36;
    private static final int HL_ICON = 24;
    private static final int HL_GAP = 4;
    private static final ResourceLocation TEX_HIGHLIGHT_ICON =
        new ResourceLocation("maniacmod", "textures/gui/vitals/highlight_icon.png");

    /**
     * Слот сили над панеллю показників — у стилі слотів маньяка
     * ({@code ManiacHotbarOverlay}): темний фон, рамка, іконка по центру.
     *
     *   • зверху зліва — клавіша активації (за замовчуванням «5»; беремо
     *     справжню назву прив'язки, тож перепризначення видно одразу);
     *   • готова — золота рамка й яскрава іконка;
     *   • перезаряджається — іконка тьмяніє, темна завіса опускається зверху
     *     вниз і скорочується разом із часом, по центру — залишок у секундах.
     *
     * Дані ті самі, що й у кулдаунів маньяка ({@code abilityCooldownFraction}):
     * сервер шле тривалість один раз, клієнт відраховує сам.
     */
    private static void renderHighlightSlot(GuiGraphics graphics, Minecraft mc, int panelLeft, int panelTop) {
        long tick = mc.level != null ? mc.level.getGameTime() : 0L;
        // Частка, що ЛИШИЛАСЬ: 1 — щойно використали, 0 — готово.
        float remaining = ClientMatchState.abilityCooldownFraction(AbilityCooldownPacket.HIGHLIGHT_ID, tick);
        boolean ready = remaining <= 0f;

        int x = panelLeft;
        int y = panelTop - HL_GAP - HL_SLOT;

        graphics.fill(x, y, x + HL_SLOT, y + HL_SLOT, ManiacUiTheme.SLOT_FILL);
        ManiacUiTheme.border1px(graphics, x, y, HL_SLOT, HL_SLOT,
            ready ? ManiacUiTheme.MENU_ACCENT_GOLD : ManiacUiTheme.BORDER);

        int iconX = x + (HL_SLOT - HL_ICON) / 2;
        int iconY = y + (HL_SLOT - HL_ICON) / 2 + 2; // трохи нижче центру: угорі місце під клавішу
        float tint = ready ? 1.0f : 0.40f;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(tint, tint, tint, 1.0f);
        graphics.blit(TEX_HIGHLIGHT_ICON, iconX, iconY, HL_ICON, HL_ICON, 0f, 0f, 32, 32, 32, 32);
        // Множник ОБОВ'ЯЗКОВО назад до білого: інакше решта GUI лишилась би тонованою.
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();

        if (!ready) {
            int curtain = Math.round((HL_SLOT - 2) * remaining);
            graphics.fill(x + 1, y + 1, x + HL_SLOT - 1, y + 1 + curtain, 0xB0000000);

            // Округлення вгору: «0с» ніколи не показується, поки сила не готова.
            int seconds = (int) Math.ceil(
                ClientMatchState.abilityCooldownTicksLeft(AbilityCooldownPacket.HIGHLIGHT_ID, tick) / 20.0);
            String text = String.valueOf(seconds);
            graphics.drawString(mc.font, text,
                x + (HL_SLOT - mc.font.width(text)) / 2,
                y + (HL_SLOT - mc.font.lineHeight) / 2 + 2,
                ManiacUiTheme.TEXT_TITLE, true);
        }

        String key = ManiacKeybinds.HIGHLIGHT.getTranslatedKeyMessage().getString();
        graphics.drawString(mc.font, key, x + 4, y + 3,
            ready ? ManiacUiTheme.TEXT_ACCENT : ManiacUiTheme.TEXT_BODY, true);
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
        // Серце «стукає» ЗАВЖДИ — навіть коли маньяка поруч немає, це тихий
        // базовий пульс (амплітуда 0.10). Близькість маньяка не вмикає
        // пульсацію, а лише підсилює її (до 0.32 впритул) і прискорює
        // період — той самий принцип, що й у звуку серцебиття
        // ({@code HeartbeatSoundHandler}), з яким візуал б'ється в такт.
        long periodMs = Math.round(900 - 640 * heartbeat);
        long now = System.currentTimeMillis();
        float phase = (now % periodMs) / (float) periodMs;
        float pulse = phase < 0.18f
            ? phase / 0.18f
            : Math.max(0f, 1f - (phase - 0.18f) / 0.82f);

        float amplitude = 0.10f + 0.22f * heartbeat;
        int size = Math.round(HEART_BASE_DRAW_SIZE + pulse * HEART_BASE_DRAW_SIZE * amplitude);

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

    // ── Шкала вставання (CRAWLING) — ВЕРТИКАЛЬНА ──────────────────────────

    // ── Чому вертикальна, а не як у генератора ───────────────────────────
    // За вимогою дизайну: підняття на ноги — це стан САМОГО ГРАВЦЯ, і
    // шкала стоїть ПРАВОРУЧ від приціла, як «смуга навантаження» біля
    // персонажа, а не як панель роботи з об'єктом у світі (генератор,
    // підняття тіла). Горизонтальна панель на всю ширину екрана тут ще й
    // фізично заважала б: вона лягає рівно туди, куди дивиться лежачий
    // гравець.
    //
    // Палітра й фактура лишаються генераторні — {@link GeneratorFuelUiTheme}:
    // той самий фон панелі, рамка, заглиблений трек із фаскою й те саме
    // сріблясте заповнення. Вертикальним є СКЛАД, не стиль.
    private static final int STAND_UP_BAR_WIDTH = 12;
    private static final int STAND_UP_BAR_HEIGHT = 90;
    private static final int STAND_UP_PANEL_PADDING = 6;
    /** Відступ шкали від центру екрана ВПРАВО — щоб не закривати приціл. */
    private static final int STAND_UP_OFFSET_FROM_CENTER = 26;
    /** Проміжок між шкалою та її підписами (відсоток зверху, підказка знизу). */
    private static final int STAND_UP_TEXT_GAP = 4;

    /**
     * Шкала вставання — вертикальна, заповнюється ЗНИЗУ ВГОРУ.
     *
     * ── Знизу вгору, а не зверху вниз ────────────────────────────────
     * Заповнення росте до гори: це «піднятися», тобто рух угору, і
     * останній відсоток шкали збігається з моментом, коли гравець уже
     * встає. Горизонтальна шкала ремонту читалась «зліва направо», тут
     * такої осі немає.
     *
     * Дані лише з {@code ClientMatchState} (StandUpProgressPacket): сервер
     * тримає джерело правди (у нього прогрес щотіка згасає, тож встати
     * одним натисканням не можна — треба спамити пробіл), а клієнт лише
     * малює те, що прийшло.
     */
    private static void renderStandUpBar(GuiGraphics graphics, Minecraft mc) {
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int presses = ClientMatchState.standUpPresses();
        int required = ClientMatchState.standUpRequired();
        // required == 0 — пакет прогресу ще не дійшов (перший кадр після
        // падіння): малюємо порожню шкалу, а не ділимо на нуль.
        float fraction = required > 0 ? clamp01((float) presses / required) : 0f;
        int percent = Math.round(fraction * 100f);

        int panelW = STAND_UP_BAR_WIDTH + STAND_UP_PANEL_PADDING * 2;
        int panelH = STAND_UP_BAR_HEIGHT + STAND_UP_PANEL_PADDING * 2;
        int panelX = screenW / 2 + STAND_UP_OFFSET_FROM_CENTER;
        int panelY = screenH / 2 - panelH / 2;

        GeneratorFuelUiTheme.drawPanel(graphics, panelX, panelY, panelW, panelH);

        int barX = panelX + STAND_UP_PANEL_PADDING;
        int barY = panelY + STAND_UP_PANEL_PADDING;
        drawStandUpBarVertical(graphics, barX, barY, STAND_UP_BAR_WIDTH, STAND_UP_BAR_HEIGHT, fraction);

        String percentText = percent + "%";
        String prompt = Component.translatable("maniacmod.hud.stand_up_prompt").getString();
        int centerX = panelX + panelW / 2;

        // Відсоток — НАД шкалою, підказка — ПІД нею. Обидва підписи
        // центруються по шкалі, бо вона тепер сама собі окремий елемент, а
        // не рядок у панелі з заголовком.
        graphics.drawString(mc.font, percentText,
            centerX - mc.font.width(percentText) / 2, panelY - 10, GeneratorFuelUiTheme.TEXT, true);
        graphics.drawString(mc.font, prompt,
            centerX - mc.font.width(prompt) / 2, panelY + panelH + STAND_UP_TEXT_GAP,
            GeneratorFuelUiTheme.TEXT, true);
    }

    /**
     * Трек вертикальної шкали: заглиблення + заповнення ЗНИЗУ ВГОРУ.
     *
     * Дзеркальний до {@link GeneratorFuelUiTheme#drawBar} набір операцій із
     * тими самими кольорами: ванільний {@code fill(x1, y1, x2, y2)} бере
     * прямокутник за двома кутами, тож «висота» заливки тут рахується від
     * НИЖНЬОГО краю вгору (у горизонтальному барі — від лівого праворуч).
     */
    private static void drawStandUpBarVertical(GuiGraphics graphics, int x, int y,
                                                 int w, int h, float fraction) {
        graphics.fill(x, y, x + w, y + h, GeneratorFuelUiTheme.INSET_BG);
        GeneratorFuelUiTheme.bevelBorderInset(graphics, x, y, w, h);

        int filled = Math.round((h - 2) * clamp01(fraction));
        if (filled > 0) {
            graphics.fill(x + 1, y + h - 1 - filled, x + w - 1, y + h - 1,
                GeneratorFuelUiTheme.FILL);
        }
    }
}
