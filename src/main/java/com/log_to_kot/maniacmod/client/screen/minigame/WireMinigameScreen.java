package com.log_to_kot.maniacmod.client.screen.minigame;

import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.minigame.WireMinigameDropPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * "З'єднай дроти" — щиток із клемними колодками: 4 кольорові клеми зліва
 * («вхід»), 4 справа («вихід»), дроти на старті під'єднані НЕправильно.
 * Гравець затискає ліву клему, тягне до правої клеми ТОГО Ж кольору й
 * відпускає.
 *
 * ── Вигляд: це система, яку лагодять ─────────────────────────────────
 * Головний фон — СТАНДАРТНА панель мода ({@link ManiacUiTheme#drawPanel}),
 * така сама, як у всіх інших меню (міні-гра «ціль», вибір пасток тощо);
 * поверх неї — заглиблена монтажна плата з тонкою сіткою; дві клемні
 * колодки з гвинтовими клемами; шапка з індикаторами (по одному на кожну
 * лінію — загоряється зеленим, коли лінію сервер підтвердив);
 * сегментний таймер і попереджувальна смуга. Підтверджені дроти «живі»:
 * по них біжить імпульс. Провал фарбує панель у червоне (як і в «цілі»),
 * індикатори мигають.
 *
 * ── Дроти йдуть ТІЛЬКИ по прямих ─────────────────────────────────────
 * Жодних діагоналей: горизонталь від клеми → вертикаль у власній лівій
 * колії → власна горизонтальна «магістраль» між рядами → вертикаль у
 * власній правій колії → горизонталь до цілі. Кожен дріт має свої колії й
 * свою висоту магістралі, тому дроти можуть лише перетинатись, але ніколи не
 * йдуть по одній лінії й не зливаються. Якщо ціль на тому ж ряду — дріт
 * просто пряма лінія. Поки дріт тягнеш, він так само ортогональний, а біля
 * правої клеми «липне» до неї.
 *
 * Дріт малюється шарами (тінь → чорна ізоляція → кольорова жила → блик)
 * прямокутниками {@code fill()}: ніяких обертань матриці й текстур,
 * тому нічого не «пливе» і не тоновано (стара текстура
 * {@code gui/minigame/wire.png} більше не використовується).
 *
 * ── Хто що рахує ─────────────────────────────────────────────────────
 * Індекс лівої клеми {@code i} — це «колір»: лівий {@code i} шукає ту
 * праву клему, чий колір теж {@code i} (кольори — клієнтська константа
 * {@link #WIRE_COLORS}, сервер знає лише індекси). Праву колодку
 * ПЕРЕМІШАНО: {@link #rightColors}[slot] — колір правої клеми в рядку
 * {@code slot}, тож правильна клема стоїть у довільному рядку, а не
 * навпроти. {@link #connections}[i] — до якої правої клеми зараз
 * намальовано дріт лівої клеми {@code i}. Відпускання дроту лише
 * НАДСИЛАЄ подію серверу ({@link WireMinigameDropPacket}) — правильність
 * вирішує сервер ({@link #onWireConnected}, {@link #onResult}). Це той
 * самий принцип «довіряй, але перевіряй», що й у {@link TargetMinigameScreen}.
 *
 * ── Таймер ───────────────────────────────────────────────────────────
 * {@code timeLimitTicks} прийшов від сервера разом з відкриттям — рахуємо
 * тут лише ДЛЯ ВІДОБРАЖЕННЯ. Реальний провал по часу контролює сервер
 * ({@code ActiveRepairMinigame.tickAndCheckTimeout}) і присилає
 * {@link #onResult}.
 *
 * ── Чому не закривається по ESC ──────────────────────────────────────
 * Те саме правило, що й у {@link TargetMinigameScreen}.
 */
public final class WireMinigameScreen extends Screen {

    private static final int SLOT_COUNT = 4;
    private static final int[] WIRE_COLORS = { 0xFFE24B3B, 0xFFE8B23A, 0xFF3D7FE8, 0xFFB04BE8 };

    // ── Розкладка (px) ───────────────────────────────────────────────────
    private static final int PANEL_WIDTH = 264;
    private static final int PANEL_HEIGHT = 210;
    private static final int PAD = 14;             // відступ корпусу до внутрішніх блоків
    private static final int HEADER_TOP = 6;
    private static final int HEADER_H = 22;
    private static final int BOARD_TOP = 34;
    private static final int BOARD_H = 144;
    private static final int STRIP_MARGIN = 4;     // від краю плати до клемної колодки
    private static final int STRIP_W = 34;
    private static final int ROW_TOP = 24;         // від верху плати до першого ряду клем
    private static final int ROW_GAP = 32;
    private static final int LANE_GAP = 12;        // відстань між «доріжками» вертикалей
    private static final int TERMINAL_HALF = 11;   // половина блока клеми
    private static final int SLOT_RADIUS = 12;     // радіус влучання мишею
    private static final int TIMER_TOP = 186;
    private static final int TIMER_H = 8;
    private static final int HAZARD_H = 6;

    private static final int WIRE_THICKNESS = 6;
    private static final int HINT_HEIGHT = 22;
    private static final int HINT_GAP = 10;

    /** Скільки мс вікно лишається червоним після провалу, перш ніж закритись. */
    private static final long FAIL_LINGER_MS = 900;

    // ── Палітра: панель — зі спільної теми, решта — деталі щитка ─────────
    private static final int PLATE = ManiacUiTheme.MENU_PANEL_DARK;
    private static final int PLATE_EDGE = ManiacUiTheme.BORDER;
    private static final int BOARD_BG = 0xFF090C0E;
    private static final int GRID = 0xFF10161B;
    private static final int STRIP_BG = 0xFF171D23;
    private static final int STRIP_EDGE = 0xFF343E48;
    private static final int METAL = 0xFFAEB5BE;
    private static final int METAL_HI = 0xFFD9DEE4;
    private static final int METAL_DARK = 0xFF3A4048;
    private static final int LABEL = 0xFF6F7A86;
    private static final int LED_ON = 0xFF3FE07A;
    private static final int LED_OFF = 0xFF1B2A21;

    private static final int FAIL_BORDER = 0xFFB0242F;
    private static final int FAIL_PANEL_BG = 0xE63A0A0A;

    private final int timeLimitTicks;
    private final long openedAtMillis;

    /** rightColors[slot] = колір (індекс) правої клеми в рядку slot — права колодка перемішана. */
    private final int[] rightColors = new int[SLOT_COUNT];

    /** connections[left] = права клема, на якій зараз закінчується дріт лівої клеми. */
    private final int[] connections = new int[SLOT_COUNT];
    private final boolean[] confirmedCorrect = new boolean[SLOT_COUNT];

    private int draggingLeftSlot = -1;
    private boolean finished = false;

    /** true після провалу — корпус червоніє до закриття. */
    private boolean failed = false;

    /** Коли (мс) закрити екран після провалу; 0 — не заплановано. */
    private long closeAtMillis = 0;

    public WireMinigameScreen(int[] initialRightSlotForLeft, int[] rightColors, int timeLimitTicks) {
        super(Component.translatable("maniacmod.minigame.wires.title"));
        this.timeLimitTicks = timeLimitTicks;
        this.openedAtMillis = System.currentTimeMillis();
        System.arraycopy(initialRightSlotForLeft, 0, connections, 0,
            Math.min(SLOT_COUNT, initialRightSlotForLeft.length));
        System.arraycopy(rightColors, 0, this.rightColors, 0,
            Math.min(SLOT_COUNT, rightColors.length));
    }

    /** Чи є на правій клемі {@code slot} уже підтверджений сервером дріт. */
    private boolean isRightConfirmed(int slot) {
        for (int left = 0; left < SLOT_COUNT; left++) {
            if (confirmedCorrect[left] && connections[left] == slot) return true;
        }
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        // Навмисно порожньо — вихід без результату трактується сервером
        // як провал (дистанція/дисконект), а не як звичайне закриття.
    }

    /** Викликається ClientPacketHandler після RepairMinigameProgressPacket.wireConnected(...). */
    public void onWireConnected(int leftSlot, int rightSlot) {
        if (leftSlot < 0 || leftSlot >= SLOT_COUNT) return;
        connections[leftSlot] = rightSlot;
        confirmedCorrect[leftSlot] = true;
    }

    /**
     * Викликається ClientPacketHandler після RepairMinigameResultPacket.
     * Успіх закриває екран одразу. Провал фарбує корпус у червоне й закриває
     * його через {@link #FAIL_LINGER_MS} у {@link #tick()}.
     */
    public void onResult(boolean success) {
        if (finished) return;
        finished = true;
        draggingLeftSlot = -1; // не лишаємо дріт «в руці» на червоному екрані
        if (success) {
            Minecraft.getInstance().setScreen(null);
            return;
        }
        failed = true;
        closeAtMillis = System.currentTimeMillis() + FAIL_LINGER_MS;
    }

    @Override
    public void tick() {
        super.tick();
        if (closeAtMillis != 0 && System.currentTimeMillis() >= closeAtMillis) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    // ── Геометрія ────────────────────────────────────────────────────────

    private int panelX() { return width / 2 - PANEL_WIDTH / 2; }
    private int panelY() { return height / 2 - (PANEL_HEIGHT + HINT_HEIGHT + HINT_GAP) / 2; }
    private int boardX(int px) { return px + PAD; }
    private int boardY(int py) { return py + BOARD_TOP; }
    private int boardW() { return PANEL_WIDTH - PAD * 2; }
    private int leftSlotX(int px) { return boardX(px) + STRIP_MARGIN + STRIP_W / 2; }
    private int rightSlotX(int px) { return px + PANEL_WIDTH - PAD - STRIP_MARGIN - STRIP_W / 2; }
    private int slotY(int py, int slot) { return boardY(py) + ROW_TOP + slot * ROW_GAP; }

    /** Ліва колія вертикалі дроту: кожен дріт має свою, лівіше від центру плати. */
    private int laneLeftX(int px, int leftSlot) {
        return px + PANEL_WIDTH / 2 - 10 - leftSlot * LANE_GAP;
    }

    /** Права колія вертикалі дроту: своя в кожного дроту, правіше від центру плати. */
    private int laneRightX(int px, int leftSlot) {
        return px + PANEL_WIDTH / 2 + 10 + leftSlot * LANE_GAP;
    }

    /**
     * Горизонтальні «магістралі» (відносно верху плати) у проміжках між рядами клем —
     * по дві на проміжок, щоб кожен дріт мав ВЛАСНУ висоту й жодні два дроти
     * не йшли по одній лінії. Жодна не збігається з висотою ряду клем.
     */
    private static final int[] TRACKS = {
        ROW_TOP - 13,
        ROW_TOP + 10, ROW_TOP + 22,
        ROW_TOP + ROW_GAP + 10, ROW_TOP + ROW_GAP + 22,
        ROW_TOP + 2 * ROW_GAP + 10, ROW_TOP + 2 * ROW_GAP + 22,
        ROW_TOP + 3 * ROW_GAP + 13
    };

    /**
     * Розподіляє магістралі так, щоб дріт ішов від свого ряду до цільового
     * МОНОТОННО (без гачків уперед-назад): кожному дроту, що міняє ряд, — вільна
     * магістраль між його рядами, найближча до середини. Якщо так не виходить
     * (не вистачає місць), дозволяємо будь-яку вільну. Дроти, що йдуть по своєму
     * ряду, магістралі не потребують (-1). Розподіл детермінований (за індексом
     * дроту), тож дроти не стрибають між кадрами.
     */
    private int[] allocateTracks(int py, int[] conn) {
        int[] rel = new int[SLOT_COUNT];
        java.util.Arrays.fill(rel, -1);
        boolean[] used = new boolean[TRACKS.length];
        if (!assignTracks(0, conn, rel, used, true)) {
            java.util.Arrays.fill(rel, -1);
            java.util.Arrays.fill(used, false);
            assignTracks(0, conn, rel, used, false);
        }
        int[] result = new int[SLOT_COUNT];
        for (int k = 0; k < SLOT_COUNT; k++) result[k] = rel[k] < 0 ? -1 : boardY(py) + rel[k];
        return result;
    }

    private boolean assignTracks(int k, int[] conn, int[] rel, boolean[] used, boolean monotone) {
        if (k == SLOT_COUNT) return true;
        if (conn[k] == k) return assignTracks(k + 1, conn, rel, used, monotone);
        int yA = ROW_TOP + k * ROW_GAP;
        int yB = ROW_TOP + conn[k] * ROW_GAP;
        int lo = Math.min(yA, yB), hi = Math.max(yA, yB);
        int mid = (yA + yB) / 2;
        Integer[] order = new Integer[TRACKS.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> Integer.compare(Math.abs(TRACKS[x] - mid), Math.abs(TRACKS[y] - mid)));
        for (int t : order) {
            if (used[t]) continue;
            if (monotone && (TRACKS[t] < lo || TRACKS[t] > hi)) continue;
            used[t] = true;
            rel[k] = TRACKS[t];
            if (assignTracks(k + 1, conn, rel, used, monotone)) return true;
            used[t] = false;
            rel[k] = -1;
        }
        return false;
    }

    // ── Ввід ─────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Після вердикту клік споживаємо, але нічого не робимо: false
        // означало б «клік нікому не потрібен» і він міг би провалитись у світ.
        if (finished) return true;
        if (button != 0) return false;
        int px = panelX();
        int py = panelY();

        for (int left = 0; left < SLOT_COUNT; left++) {
            if (confirmedCorrect[left]) continue; // вже підтверджений сервером — більше не чіпаємо
            if (dist(mouseX, mouseY, leftSlotX(px), slotY(py, left)) <= SLOT_RADIUS) {
                draggingLeftSlot = left;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (finished || button != 0 || draggingLeftSlot < 0) {
            return super.mouseReleased(mouseX, mouseY, button);
        }

        int px = panelX();
        int py = panelY();
        int left = draggingLeftSlot;
        draggingLeftSlot = -1;

        int right = rightSlotAt(px, py, mouseX, mouseY);
        if (right >= 0) {
            // Клієнт лише повідомляє намір — правильність визначає сервер.
            // Локально запам'ятовуємо «куди тягли», щоб дріт не смикнувся
            // назад на старе місце на один кадр.
            connections[left] = right;
            ModNetwork.toServer(new WireMinigameDropPacket(left, right));
        }
        // Відпущено не над правою клемою — дріт лишається на попередньому місці.
        return true;
    }

    /** Права клема під курсором (із запасом на «липкість») або -1. */
    private int rightSlotAt(int px, int py, double mx, double my) {
        for (int right = 0; right < SLOT_COUNT; right++) {
            if (dist(mx, my, rightSlotX(px), slotY(py, right)) <= SLOT_RADIUS + 4) return right;
        }
        return -1;
    }

    private static double dist(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2, dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    // ── Малювання ────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Навмисно НЕ renderBackground — гру видно позаду щитка.
        int px = panelX();
        int py = panelY();
        long now = System.currentTimeMillis();

        drawPanelFrame(g, px, py, PANEL_WIDTH, PANEL_HEIGHT);
        drawHeader(g, px, py, now);
        drawBoard(g, px, py);
        drawStrips(g, px, py);

        int hoverRight = draggingLeftSlot >= 0 ? rightSlotAt(px, py, mouseX, mouseY) : -1;

        // Дроти: спершу підтверджені (під низом), потім решта, перетягуваний — зверху.
        for (int pass = 0; pass < 3; pass++) {
            for (int left = 0; left < SLOT_COUNT; left++) {
                boolean dragging = draggingLeftSlot == left;
                int layer = dragging ? 2 : (confirmedCorrect[left] ? 0 : 1);
                if (layer != pass) continue;
                drawWireFor(g, px, py, left, dragging, hoverRight, mouseX, mouseY, now);
            }
        }

        // Клеми поверх країв дротів.
        for (int i = 0; i < SLOT_COUNT; i++) {
            int ly = slotY(py, i);
            boolean hoverL = !finished && draggingLeftSlot < 0 && !confirmedCorrect[i]
                && dist(mouseX, mouseY, leftSlotX(px), ly) <= SLOT_RADIUS;
            drawTerminal(g, leftSlotX(px), ly, WIRE_COLORS[i], confirmedCorrect[i], hoverL || draggingLeftSlot == i);
            drawTerminal(g, rightSlotX(px), ly, WIRE_COLORS[rightColors[i]], isRightConfirmed(i), hoverRight == i);
        }

        drawTimer(g, px, py, now);
        drawHazard(g, px + PAD, py + PANEL_HEIGHT - 10, PANEL_WIDTH - PAD * 2, HAZARD_H);

        // Підказка під щитком.
        int hintY = py + PANEL_HEIGHT + HINT_GAP;
        double remainingSeconds = remainingSeconds(now);
        String hintText = Component.translatable(
            "maniacmod.minigame.wires.time_left", (int) Math.ceil(remainingSeconds)).getString();
        if (failed) {
            g.fill(px, hintY, px + PANEL_WIDTH, hintY + HINT_HEIGHT, FAIL_PANEL_BG);
            ManiacUiTheme.border1px(g, px, hintY, PANEL_WIDTH, HINT_HEIGHT, FAIL_BORDER);
            g.drawCenteredString(font, hintText, px + PANEL_WIDTH / 2,
                hintY + (HINT_HEIGHT - font.lineHeight) / 2, FAIL_BORDER);
        } else {
            ManiacUiTheme.drawHintBar(g, font, hintText, px, hintY, PANEL_WIDTH, HINT_HEIGHT);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private double remainingSeconds(long now) {
        return Math.max(0, timeLimitTicks / 20.0 - (now - openedAtMillis) / 1000.0);
    }

    // ── Корпус, шапка, плата ────────────────────────────────────────────

    /**
     * Головна панель — та сама, що в усіх меню мода. У провалі фарбується
     * в червоне так само, як панель міні-гри «ціль».
     */
    private void drawPanelFrame(GuiGraphics g, int x, int y, int w, int h) {
        if (!failed) {
            ManiacUiTheme.drawPanel(g, x, y, w, h);
            return;
        }
        g.fill(x, y, x + w, y + h, FAIL_PANEL_BG);
        ManiacUiTheme.border1px(g, x, y, w, h, FAIL_BORDER);
        int t = 2, c = 10;
        g.fill(x, y, x + c, y + t, FAIL_BORDER);
        g.fill(x, y, x + t, y + c, FAIL_BORDER);
        g.fill(x + w - c, y, x + w, y + t, FAIL_BORDER);
        g.fill(x + w - t, y, x + w, y + c, FAIL_BORDER);
        g.fill(x, y + h - t, x + c, y + h, FAIL_BORDER);
        g.fill(x, y + h - c, x + t, y + h, FAIL_BORDER);
        g.fill(x + w - c, y + h - t, x + w, y + h, FAIL_BORDER);
        g.fill(x + w - t, y + h - c, x + w, y + h, FAIL_BORDER);
    }

    /** Шапка: заголовок і по індикатору на кожну лінію. */
    private void drawHeader(GuiGraphics g, int px, int py, long now) {
        int x = px + PAD;
        int y = py + HEADER_TOP;
        int w = PANEL_WIDTH - PAD * 2;
        g.fill(x, y, x + w, y + HEADER_H, PLATE);
        ManiacUiTheme.border1px(g, x, y, w, HEADER_H, failed ? FAIL_BORDER : PLATE_EDGE);

        String text = title.getString().toUpperCase(Locale.ROOT);
        g.drawString(font, text, x + 8, y + (HEADER_H - font.lineHeight) / 2 + 1,
            failed ? 0xFFFF8A8A : ManiacUiTheme.TEXT_TITLE, false);

        // Індикатори ліній.
        boolean blink = ((now / 220) & 1) == 0;
        int ledStep = 12;
        int ledX = x + w - 8 - (SLOT_COUNT - 1) * ledStep - 3;
        int ledY = y + HEADER_H / 2;
        for (int i = 0; i < SLOT_COUNT; i++) {
            int cx = ledX + i * ledStep;
            int color;
            boolean lit;
            if (failed) {
                lit = blink;
                color = 0xFFE03A3A;
            } else {
                lit = confirmedCorrect[i];
                color = LED_ON;
            }
            g.fill(cx - 4, ledY - 4, cx + 4, ledY + 4, 0xFF06080A);
            if (lit) {
                g.fill(cx - 6, ledY - 6, cx + 6, ledY + 6, withAlpha(color, 0x2A));
                g.fill(cx - 3, ledY - 3, cx + 3, ledY + 3, color);
                g.fill(cx - 2, ledY - 2, cx, ledY, 0xB0FFFFFF);
            } else {
                g.fill(cx - 3, ledY - 3, cx + 3, ledY + 3, failed ? 0xFF2A1010 : LED_OFF);
            }
        }
    }

    /** Заглиблена монтажна плата з тонкою сіткою. */
    private void drawBoard(GuiGraphics g, int px, int py) {
        int x = boardX(px);
        int y = boardY(py);
        int w = boardW();
        int h = BOARD_H;
        g.fill(x, y, x + w, y + h, BOARD_BG);
        for (int gx = x + 12; gx < x + w - 1; gx += 12) g.fill(gx, y + 1, gx + 1, y + h - 1, GRID);
        for (int gy = y + 12; gy < y + h - 1; gy += 12) g.fill(x + 1, gy, x + w - 1, gy + 1, GRID);
        // Заглиблення: тінь зверху-зліва, світло знизу-справа.
        g.fill(x, y, x + w, y + 2, 0xFF000000);
        g.fill(x, y, x + 2, y + h, 0xFF000000);
        g.fill(x, y + h - 1, x + w, y + h, ManiacUiTheme.BORDER);
        g.fill(x + w - 1, y, x + w, y + h, ManiacUiTheme.BORDER);
        if (failed) g.fill(x, y, x + w, y + h, 0x30B0242F);
    }

    /** Дві клемні колодки з підписами «вхід»/«вихід». */
    private void drawStrips(GuiGraphics g, int px, int py) {
        int y0 = boardY(py) + 4;
        int y1 = boardY(py) + BOARD_H - 4;
        int lx = boardX(px) + STRIP_MARGIN;
        int rx = px + PANEL_WIDTH - PAD - STRIP_MARGIN - STRIP_W;
        for (int x : new int[] { lx, rx }) {
            g.fill(x, y0, x + STRIP_W, y1, STRIP_BG);
            ManiacUiTheme.border1px(g, x, y0, STRIP_W, y1 - y0, failed ? 0xFF6A2026 : STRIP_EDGE);
            g.fill(x + 1, y0 + 1, x + STRIP_W - 1, y0 + 2, 0x14FFFFFF);
        }
        drawLabel(g, Component.translatable("maniacmod.minigame.wires.input").getString(), lx + STRIP_W / 2, y0 + 5);
        drawLabel(g, Component.translatable("maniacmod.minigame.wires.output").getString(), rx + STRIP_W / 2, y0 + 5);
    }

    private void drawLabel(GuiGraphics g, String text, int cx, int y) {
        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx, y, 0);
        pose.scale(0.75f, 0.75f, 1f);
        g.drawString(font, text, -font.width(text) / 2, 0, LABEL, false);
        pose.popPose();
    }

    // ── Клема ───────────────────────────────────────────────────────────

    /** Гвинтова клема: темний блок, кольоровий комір, металева головка гвинта. */
    private void drawTerminal(GuiGraphics g, int cx, int cy, int color, boolean confirmed, boolean hot) {
        int h = TERMINAL_HALF;
        g.fill(cx - h, cy - h, cx + h, cy + h, 0xFF0B0E11);
        ManiacUiTheme.border1px(g, cx - h, cy - h, h * 2, h * 2, confirmed ? LED_ON : 0xFF3B4550);
        g.fill(cx - h + 1, cy - h + 1, cx + h - 1, cy - h + 2, 0x18FFFFFF);
        float dim = failed ? 0.55f : 1f;
        disc(g, cx, cy, 8, mul(color, 0.45f * dim));
        disc(g, cx, cy, 7, mul(color, dim));
        disc(g, cx - 2, cy - 3, 2, mix(mul(color, dim), 0xFFFFFFFF, 0.45f));
        disc(g, cx, cy, 4, METAL);
        disc(g, cx - 1, cy - 1, 2, METAL_HI);
        g.fill(cx - 3, cy, cx + 3, cy + 1, METAL_DARK);            // шліц гвинта
        if (hot) {
            ManiacUiTheme.border1px(g, cx - h - 2, cy - h - 2, h * 2 + 4, h * 2 + 4, 0xFFF2F4F8);
        }
    }

    // ── Дроти ───────────────────────────────────────────────────────────

    private void drawWireFor(GuiGraphics g, int px, int py, int left, boolean dragging,
                             int hoverRight, int mouseX, int mouseY, long now) {
        int sx = leftSlotX(px) + TERMINAL_HALF;
        int sy = slotY(py, left);
        int color = WIRE_COLORS[left];

        int[] pts;
        boolean freeEnd = false;
        if (dragging && hoverRight < 0) {
            // Вільний кінець: горизонталь → вертикаль → горизонталь до курсору.
            int vx = Math.max(sx, Math.min(laneLeftX(px, left), mouseX));
            pts = new int[] { sx, sy, vx, sy, vx, mouseY, mouseX, mouseY };
            freeEnd = true;
        } else {
            // Постійний або «прилиплий» до правої клеми: магістралі рахуємо з урахуванням цього дроту.
            int[] conn = connections.clone();
            if (dragging) conn[left] = hoverRight;
            int[] tracks = allocateTracks(py, conn);
            pts = route(px, py, left, conn[left], tracks[left]);
        }

        drawPath(g, pts, color);
        drawFerrule(g, sx - 1, sy, true);
        if (freeEnd) {
            drawPlug(g, mouseX, mouseY);
        } else {
            drawFerrule(g, pts[pts.length - 2] + 1, pts[pts.length - 1], false);
        }
        if (confirmedCorrect[left] && !failed) drawPulse(g, pts, now, left);
    }

    /**
     * Маршрут дроту лише з горизонталей і вертикалей. Той самий ряд — пряма
     * лінія. Інакше: горизонталь від клеми → вертикаль у лівій колії → власна
     * магістраль → вертикаль у правій колії → горизонталь до клеми. Ліві колії
     * лежать лівіше від центру, праві — правіше, тож початок одного дроту
     * ніколи не накладається на кінець іншого на тому самому ряду.
     */
    private int[] route(int px, int py, int left, int right, int trackY) {
        int sx = leftSlotX(px) + TERMINAL_HALF;
        int ex = rightSlotX(px) - TERMINAL_HALF;
        int ya = slotY(py, left);
        int yb = slotY(py, right);
        if (ya == yb) return new int[] { sx, ya, ex, yb };
        int xl = laneLeftX(px, left);
        int xr = laneRightX(px, left);
        return new int[] { sx, ya, xl, ya, xl, trackY, xr, trackY, xr, yb, ex, yb };
    }

    /** Дріт шарами: тінь → чорна ізоляція → кольорова жила → блик. Усі сегменти суворо горизонтальні/вертикальні. */
    private void drawPath(GuiGraphics g, int[] p, int color) {
        int n = p.length / 2 - 1;
        int h = WIRE_THICKNESS / 2;
        float dim = failed ? 0.55f : 1f;
        int core = mul(color, 0.9f * dim);
        int light = mix(mul(color, dim), 0xFFFFFFFF, 0.42f);
        int dark = mul(color, 0.55f * dim);

        for (int i = 0; i < n; i++) segFill(g, p, i, h + 1, 0, 2, 0x55000000);       // тінь
        for (int i = 0; i < n; i++) segFill(g, p, i, h + 1, 0, 0, 0xFF07090B);        // ізоляція
        for (int i = 0; i < n; i++) segFill(g, p, i, h, 0, 0, core);                  // жила
        for (int i = 0; i < n; i++) {                                                 // нижня тінь жили + блик
            int x1 = p[i * 2], y1 = p[i * 2 + 1], x2 = p[i * 2 + 2], y2 = p[i * 2 + 3];
            if (y1 == y2) {
                int xa = Math.min(x1, x2), xb = Math.max(x1, x2);
                g.fill(xa, y1 + h - 1, xb, y1 + h, dark);
                g.fill(xa, y1 - h + 1, xb, y1 - h + 2, light);
            } else {
                int ya = Math.min(y1, y2), yb = Math.max(y1, y2);
                g.fill(x1 + h - 1, ya, x1 + h, yb, dark);
                g.fill(x1 - h + 1, ya, x1 - h + 2, yb, light);
            }
        }
    }

    private static void segFill(GuiGraphics g, int[] p, int i, int h, int dx, int dy, int color) {
        int x1 = p[i * 2], y1 = p[i * 2 + 1], x2 = p[i * 2 + 2], y2 = p[i * 2 + 3];
        g.fill(Math.min(x1, x2) - h + dx, Math.min(y1, y2) - h + dy,
            Math.max(x1, x2) + h + dx, Math.max(y1, y2) + h + dy, color);
    }

    /** Металева гільза на кінці дроту біля клеми. */
    private void drawFerrule(GuiGraphics g, int x, int cy, boolean startSide) {
        int x0 = startSide ? x : x - 7;
        g.fill(x0 - 1, cy - 5, x0 + 8, cy + 5, 0xFF07090B);
        g.fill(x0, cy - 4, x0 + 7, cy + 4, METAL);
        g.fill(x0, cy - 4, x0 + 7, cy - 3, METAL_HI);
        g.fill(x0, cy + 3, x0 + 7, cy + 4, METAL_DARK);
    }

    /** Штекер на вільному кінці дроту, який тягнуть. */
    private void drawPlug(GuiGraphics g, int cx, int cy) {
        g.fill(cx - 5, cy - 5, cx + 5, cy + 5, 0xFF07090B);
        g.fill(cx - 4, cy - 4, cx + 4, cy + 4, METAL);
        g.fill(cx - 4, cy - 4, cx + 4, cy - 3, METAL_HI);
        g.fill(cx - 4, cy + 3, cx + 4, cy + 4, METAL_DARK);
    }

    /** Імпульс струму, що біжить по підтвердженому дроту. */
    private void drawPulse(GuiGraphics g, int[] p, long now, int seed) {
        int total = 0;
        for (int i = 0; i + 3 < p.length; i += 2) total += Math.abs(p[i + 2] - p[i]) + Math.abs(p[i + 3] - p[i + 1]);
        if (total <= 0) return;
        float pos = ((now + seed * 260L) % 1500L) / 1500f * total;
        for (int t = 0; t < 3; t++) {
            int[] pt = pointAt(p, Math.max(0, pos - t * 7));
            int a = 0xE0 - t * 0x55;
            int r = 2 - (t == 2 ? 1 : 0);
            g.fill(pt[0] - r, pt[1] - r, pt[0] + r, pt[1] + r, (a << 24) | 0xFFFFFF);
        }
    }

    private static int[] pointAt(int[] p, float dist) {
        float left = dist;
        for (int i = 0; i + 3 < p.length; i += 2) {
            int x1 = p[i], y1 = p[i + 1], x2 = p[i + 2], y2 = p[i + 3];
            int len = Math.abs(x2 - x1) + Math.abs(y2 - y1);
            if (left <= len) {
                float f = len == 0 ? 0f : left / len;
                return new int[] { Math.round(x1 + (x2 - x1) * f), Math.round(y1 + (y2 - y1) * f) };
            }
            left -= len;
        }
        return new int[] { p[p.length - 2], p[p.length - 1] };
    }

    // ── Таймер і попереджувальна смуга ──────────────────────────────────

    /** Сегментний таймер: зелений → бурштиновий → червоний (мигає в кінці). */
    private void drawTimer(GuiGraphics g, int px, int py, long now) {
        int x = px + PAD;
        int y = py + TIMER_TOP;
        int w = PANEL_WIDTH - PAD * 2;
        g.fill(x, y, x + w, y + TIMER_H, 0xFF06080A);
        ManiacUiTheme.border1px(g, x, y, w, TIMER_H, failed ? FAIL_BORDER : PLATE_EDGE);

        double total = Math.max(0.05, timeLimitTicks / 20.0);
        float frac = failed ? 0f : (float) Math.min(1.0, remainingSeconds(now) / total);
        int color = frac > 0.5f ? 0xFF3FBF6F : frac > 0.25f ? 0xFFE0A030 : 0xFFD03A3A;
        if (frac <= 0.25f && ((now / 250) & 1) == 0) color = mul(color, 0.55f);

        int segs = 30;
        int innerW = w - 4;
        float segW = innerW / (float) segs;
        int lit = Math.round(frac * segs);
        for (int i = 0; i < lit; i++) {
            int sx0 = x + 2 + Math.round(i * segW);
            int sx1 = x + 2 + Math.round((i + 1) * segW) - 1;
            g.fill(sx0, y + 2, sx1, y + TIMER_H - 2, color);
        }
    }

    /** Попереджувальна смуга (жовто-чорна). Смуга — декор щитка, дроти нею не є. */
    private void drawHazard(GuiGraphics g, int x, int y, int w, int h) {
        int dark = failed ? 0xFF1A0A0C : 0xFF0D0F11;
        int stripe = failed ? 0xFFB0242F : 0xFFE0A81E;
        g.fill(x, y, x + w, y + h, dark);
        int period = 12;
        for (int r = 0; r < h; r++) {
            for (int sx = x - period + (r % period); sx < x + w; sx += period) {
                int x0 = Math.max(x, sx);
                int x1 = Math.min(x + w, sx + period / 2);
                if (x1 > x0) g.fill(x0, y + r, x1, y + r + 1, stripe);
            }
        }
        ManiacUiTheme.border1px(g, x, y, w, h, 0xFF050607);
    }

    // ── Дрібні помічники малювання ──────────────────────────────────────

    /** Заповнений круг радіуса r (діаметр 2r) горизонтальними рядками. */
    private static void disc(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy < r; dy++) {
            double yy = dy + 0.5;
            int half = (int) Math.round(Math.sqrt(Math.max(0, r * r - yy * yy)));
            if (half > 0) g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
        }
    }

    private static int mul(int argb, float k) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * k));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * k));
        int b = Math.min(255, Math.round((argb & 0xFF) * k));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int gg = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (gg << 8) | bl;
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
