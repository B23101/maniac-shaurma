package com.log_to_kot.maniacmod.client.screen.minigame;

import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.minigame.WireMinigameDropPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "З'єднай дроти" — 4 кольорові контакти зліва, 4 справа, навмисно
 * перехрещені й під'єднані НЕправильно на старті (дизайн-скрін:
 * червоний/жовтий/синій/фіолетовий). Гравець затискає лівий контакт,
 * тягне до правого контакту ТОГО Ж кольору, відпускає.
 *
 * Вигляд — {@link ManiacUiTheme}: та сама темна панель з кутовими
 * акцентами й заголовком-капсом, що й в інших екранах цього сімейства
 * (див. {@link TargetMinigameScreen}) — контакти й дроти лишаються
 * кольоровими поверх спільного темного фону панелі.
 *
 * ── Хто що рахує ─────────────────────────────────────────────────────
 * Індекс контакту {@code i} — це "колір": лівий {@code i} завжди
 * шукає правий {@code i}, незалежно від того, як саме розфарбовано на
 * екрані (кольори — суто клієнтська константа {@link #WIRE_COLORS},
 * сервер про них не знає). {@link #connections}[i] — до якого правого
 * контакту зараз намальовано дріт лівого контакту {@code i}; на
 * старті рівне тому, що прийшло в {@code WireMinigameOpenPacket}
 * (навмисно неправильна розкладка). Відпускання дроту лише НАДСИЛАЄ
 * подію серверу ({@link WireMinigameDropPacket}) — сам екран НЕ
 * вирішує "правильно/неправильно" і не малює дріт на новому місці,
 * доки не прийде підтвердження {@code RepairMinigameProgressPacket}
 * ({@link #onWireConnected}) чи миттєвий провал через
 * {@link #onResult}. Це той самий принцип "довіряй, але перевіряй",
 * що й у {@link TargetMinigameScreen}.
 *
 * ── Таймер ───────────────────────────────────────────────────────────
 * {@code timeLimitTicks} прийшов від сервера разом з відкриттям —
 * рахуємо тут лише ДЛЯ ВІДОБРАЖЕННЯ (клієнтська секундна стрілка).
 * Реальний provал через вичерпаний час контролює сервер
 * ({@code ActiveRepairMinigame.tickAndCheckTimeout}) і присилає
 * {@link #onResult} сам — цей екран нічого не робить, коли клієнтський
 * лічильник добігає нуля, він і далі чекає на серверний вердикт.
 *
 * ── Чому не закривається по ESC ──────────────────────────────────────
 * Те саме правило, що й у {@link TargetMinigameScreen} — див. той
 * клас-докстрінг.
 */
public final class WireMinigameScreen extends Screen {

    private static final int SLOT_COUNT = 4;
    private static final int[] WIRE_COLORS = { 0xFFE04030, 0xFFE0A030, 0xFF3070E0, 0xFFA030E0 };

    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_HEIGHT = 210;
    private static final int SLOT_RADIUS = 8;
    private static final int ROW_GAP = 34;
    private static final int HINT_HEIGHT = 22;
    private static final int HINT_GAP = 10;

    private final int timeLimitTicks;
    private final long openedAtMillis;

    /** connections[left] = right contact the wire currently ends at. */
    private final int[] connections = new int[SLOT_COUNT];
    private final boolean[] confirmedCorrect = new boolean[SLOT_COUNT];

    private int draggingLeftSlot = -1;
    private boolean finished = false;

    public WireMinigameScreen(int[] initialRightSlotForLeft, int timeLimitTicks) {
        super(Component.translatable("maniacmod.minigame.wires.title"));
        this.timeLimitTicks = timeLimitTicks;
        this.openedAtMillis = System.currentTimeMillis();
        System.arraycopy(initialRightSlotForLeft, 0, connections, 0,
            Math.min(SLOT_COUNT, initialRightSlotForLeft.length));
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

    /** Викликається ClientPacketHandler після RepairMinigameResultPacket — закриває екран у будь-якому разі. */
    public void onResult(boolean success) {
        if (finished) return;
        finished = true;
        Minecraft.getInstance().setScreen(null);
    }

    private int panelX() { return width / 2 - PANEL_WIDTH / 2; }
    private int panelY() { return height / 2 - (PANEL_HEIGHT + HINT_HEIGHT + HINT_GAP) / 2; }
    private int leftSlotX(int panelX) { return panelX + 30; }
    private int rightSlotX(int panelX) { return panelX + PANEL_WIDTH - 30; }
    private int slotY(int panelY, int slot) { return panelY + 68 + slot * ROW_GAP; }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (finished || button != 0) return false;
        int panelX = panelX();
        int panelY = panelY();

        for (int left = 0; left < SLOT_COUNT; left++) {
            if (confirmedCorrect[left]) continue; // вже підтверджений сервером — більше не чіпаємо
            int lx = leftSlotX(panelX);
            int ly = slotY(panelY, left);
            if (dist(mouseX, mouseY, lx, ly) <= SLOT_RADIUS + 2) {
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

        int panelX = panelX();
        int panelY = panelY();
        int left = draggingLeftSlot;
        draggingLeftSlot = -1;

        for (int right = 0; right < SLOT_COUNT; right++) {
            int rx = rightSlotX(panelX);
            int ry = slotY(panelY, right);
            if (dist(mouseX, mouseY, rx, ry) <= SLOT_RADIUS + 2) {
                // Клієнт лише повідомляє намір — правильність визначає
                // сервер (див. клас-докстрінг). Локально запам'ятовуємо
                // "куди тягли" тільки для домальовування дроту ДО
                // відповіді сервера, щоб дріт не смикався назад на
                // старе місце на один кадр.
                connections[left] = right;
                ModNetwork.toServer(new WireMinigameDropPacket(left, right));
                return true;
            }
        }
        // Відпущено не над жодним правим контактом — дріт лишається
        // на попередньому connections[left], нічого не надсилаємо.
        return true;
    }

    private static double dist(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2, dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Навмисно НЕ renderBackground — той самий принцип, що
        // TargetMinigameScreen: гру видно позаду панелі.
        int panelX = panelX();
        int panelY = panelY();

        ManiacUiTheme.drawPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);
        ManiacUiTheme.drawTitle(graphics, font, title, panelX, panelY, PANEL_WIDTH);

        for (int left = 0; left < SLOT_COUNT; left++) {
            int lx = leftSlotX(panelX);
            int ly = slotY(panelY, left);
            int color = WIRE_COLORS[left];

            // Дріт: від лівого контакту або до поточного пов'язаного
            // правого контакту, або (поки перетягуємо) до курсору.
            int rightSlot = connections[left];
            int endX, endY;
            if (draggingLeftSlot == left) {
                endX = mouseX;
                endY = mouseY;
            } else {
                endX = rightSlotX(panelX);
                endY = slotY(panelY, rightSlot);
            }
            drawWire(graphics, lx, ly, endX, endY, color);

            drawSlot(graphics, lx, ly, color);
        }

        for (int right = 0; right < SLOT_COUNT; right++) {
            int rx = rightSlotX(panelX);
            int ry = slotY(panelY, right);
            drawSlot(graphics, rx, ry, WIRE_COLORS[right]);
        }

        int hintY = panelY + PANEL_HEIGHT + HINT_GAP;
        double remainingSeconds = Math.max(0,
            timeLimitTicks / 20.0 - (System.currentTimeMillis() - openedAtMillis) / 1000.0);
        ManiacUiTheme.drawHintBar(graphics, font,
            Component.translatable("maniacmod.minigame.wires.time_left", (int) Math.ceil(remainingSeconds)).getString(),
            panelX, hintY, PANEL_WIDTH, HINT_HEIGHT);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Контакт: темна обвідка + кольорове ядро, той самий "капсульний" принцип, що прогрес-бари теми. */
    private void drawSlot(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x - SLOT_RADIUS, y - SLOT_RADIUS, x + SLOT_RADIUS, y + SLOT_RADIUS, ManiacUiTheme.PANEL_BG_SOLID);
        ManiacUiTheme.border1px(graphics, x - SLOT_RADIUS, y - SLOT_RADIUS, SLOT_RADIUS * 2, SLOT_RADIUS * 2, ManiacUiTheme.BORDER_BRIGHT);
        graphics.fill(x - SLOT_RADIUS + 3, y - SLOT_RADIUS + 3, x + SLOT_RADIUS - 3, y + SLOT_RADIUS - 3, color);
    }

    /** Лінія товщиною ~2px через послідовність невеликих заповнень — без залежності від тесселятора ліній. */
    private void drawWire(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(1, (int) dist(x1, y1, x2, y2) / 4);
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            int x = (int) (x1 + (x2 - x1) * t);
            int y = (int) (y1 + (y2 - y1) * t);
            graphics.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
