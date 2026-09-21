package com.log_to_kot.maniacmod.client.screen.minigame;

import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.minigame.WireMinigameDropPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.systems.RenderSystem;

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
 * ── Дріт = текстура, а не лінія ──────────────────────────────────────
 * Дріт малюється текстурою {@link #TEX_WIRE} (нейтральна сіра оплітка з
 * об'ємом), РОЗТЯГНУТОЮ вздовж дроту: прямокутник завдовжки як відстань
 * між кінцями, повернутий на кут лінії. Колір накладається тонуванням
 * ({@code setShaderColor}), тому одна текстура обслуговує всі чотири
 * кольори. Раніше дріт був ланцюжком крапок-квадратів {@code fill()}.
 *
 * ── Помилка ──────────────────────────────────────────────────────────
 * Неправильний дріт або вихід часу фарбує вікно в червоне; воно лишається
 * червоним, доки екран не закриється ({@link #FAIL_LINGER_MS} після
 * серверного вердикту).
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

    /** Текстура дроту: 16×8, нейтральна — колір дає тонування. */
    private static final ResourceLocation TEX_WIRE =
        new ResourceLocation("maniacmod", "textures/gui/minigame/wire.png");
    private static final int WIRE_TEX_W = 16;
    private static final int WIRE_TEX_H = 8;

    /** Товщина дроту на екрані, px (висота розтягнутої текстури). */
    private static final int WIRE_THICKNESS = 6;

    /** Скільки мс вікно лишається червоним після провалу, перш ніж закритись. */
    private static final long FAIL_LINGER_MS = 900;

    private static final int FAIL_PANEL_BG = 0xE63A0A0A;
    private static final int FAIL_BORDER = 0xFFB0242F;

    private final int timeLimitTicks;
    private final long openedAtMillis;

    /** connections[left] = right contact the wire currently ends at. */
    private final int[] connections = new int[SLOT_COUNT];
    private final boolean[] confirmedCorrect = new boolean[SLOT_COUNT];

    private int draggingLeftSlot = -1;
    private boolean finished = false;

    /** true після провалу — вікно червоніє до закриття. */
    private boolean failed = false;

    /** Коли (мс) закрити екран після провалу; 0 — не заплановано. */
    private long closeAtMillis = 0;

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

    /**
     * Викликається ClientPacketHandler після RepairMinigameResultPacket.
     * Успіх закриває екран одразу. Провал (неправильний дріт, вихід часу,
     * дистанція) фарбує вікно в червоне й закриває його через
     * {@link #FAIL_LINGER_MS} у {@link #tick()}.
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

    private int panelX() { return width / 2 - PANEL_WIDTH / 2; }
    private int panelY() { return height / 2 - (PANEL_HEIGHT + HINT_HEIGHT + HINT_GAP) / 2; }
    private int leftSlotX(int panelX) { return panelX + 30; }
    private int rightSlotX(int panelX) { return panelX + PANEL_WIDTH - 30; }
    private int slotY(int panelY, int slot) { return panelY + 68 + slot * ROW_GAP; }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Після вердикту (червоне вікно) клік споживаємо, але нічого не
        // робимо: повернути false означало б «клік нікому не потрібен»
        // і він міг би провалитись у світ під панеллю.
        if (finished) return true;
        if (button != 0) return false;
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

        drawPanelFrame(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);
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
        String hintText = Component.translatable(
            "maniacmod.minigame.wires.time_left", (int) Math.ceil(remainingSeconds)).getString();
        if (failed) {
            graphics.fill(panelX, hintY, panelX + PANEL_WIDTH, hintY + HINT_HEIGHT, FAIL_PANEL_BG);
            ManiacUiTheme.border1px(graphics, panelX, hintY, PANEL_WIDTH, HINT_HEIGHT, FAIL_BORDER);
            graphics.drawCenteredString(font, hintText, panelX + PANEL_WIDTH / 2,
                hintY + (HINT_HEIGHT - font.lineHeight) / 2, FAIL_BORDER);
        } else {
            ManiacUiTheme.drawHintBar(graphics, font, hintText, panelX, hintY, PANEL_WIDTH, HINT_HEIGHT);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Контакт: темна обвідка + кольорове ядро, той самий "капсульний" принцип, що прогрес-бари теми. */
    private void drawSlot(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x - SLOT_RADIUS, y - SLOT_RADIUS, x + SLOT_RADIUS, y + SLOT_RADIUS, ManiacUiTheme.PANEL_BG_SOLID);
        ManiacUiTheme.border1px(graphics, x - SLOT_RADIUS, y - SLOT_RADIUS, SLOT_RADIUS * 2, SLOT_RADIUS * 2, ManiacUiTheme.BORDER_BRIGHT);
        graphics.fill(x - SLOT_RADIUS + 3, y - SLOT_RADIUS + 3, x + SLOT_RADIUS - 3, y + SLOT_RADIUS - 3, color);
    }

    /**
     * Дріт: текстура {@link #TEX_WIRE}, РОЗТЯГНУТА вздовж лінії між двома
     * точками. Малюється прямокутник завдовжки як відстань між кінцями
     * й товщиною {@link #WIRE_THICKNESS}, після чого матриця повертається
     * на кут лінії (початок координат — у першій точці), тож дріт іде під
     * будь-яким кутом, а не лише горизонтально.
     *
     * Колір дає {@code setShaderColor}: текстура нейтральна (сіра), і одна
     * картинка обслуговує всі кольори. Після малювання множник ОБОВ'ЯЗКОВО
     * повертається до білого — інакше всі наступні елементи GUI (і сам
     * Minecraft) лишились би тонованими.
     *
     * У провалі дріт тьмяніє (множник до 55%), щоб не конфліктувати з
     * червоним вікном, але лишався впізнаваним за кольором.
     */
    private void drawWire(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        int length = Math.max(1, (int) Math.round(Math.sqrt(dx * dx + dy * dy)));
        float angle = (float) Math.atan2(dy, dx);

        float dim = failed ? 0.55f : 1.0f;
        float r = ((color >> 16) & 0xFF) / 255f * dim;
        float g = ((color >> 8) & 0xFF) / 255f * dim;
        float b = (color & 0xFF) / 255f * dim;

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x1, y1, 0);
        pose.mulPose(com.mojang.math.Axis.ZP.rotation(angle));

        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(r, g, b, 1.0f);
        // Текстура тягнеться на всю довжину (uWidth = довжина у px, але
        // регіон джерела — вся 16×8 картинка), тобто РОЗТЯГУЄТЬСЯ, а не
        // повторюється плиткою.
        graphics.blit(TEX_WIRE, 0, -WIRE_THICKNESS / 2, length, WIRE_THICKNESS,
            0f, 0f, WIRE_TEX_W, WIRE_TEX_H, WIRE_TEX_W, WIRE_TEX_H);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();

        pose.popPose();
    }

    /** Панель міні-гри; у провалі — червона (фон, рамка, кутові акценти). */
    private void drawPanelFrame(GuiGraphics graphics, int x, int y, int w, int h) {
        if (!failed) {
            ManiacUiTheme.drawPanel(graphics, x, y, w, h);
            return;
        }
        graphics.fill(x, y, x + w, y + h, FAIL_PANEL_BG);
        ManiacUiTheme.border1px(graphics, x, y, w, h, FAIL_BORDER);
        int t = 2, c = 10;
        graphics.fill(x, y, x + c, y + t, FAIL_BORDER);
        graphics.fill(x, y, x + t, y + c, FAIL_BORDER);
        graphics.fill(x + w - c, y, x + w, y + t, FAIL_BORDER);
        graphics.fill(x + w - t, y, x + w, y + c, FAIL_BORDER);
        graphics.fill(x, y + h - t, x + c, y + h, FAIL_BORDER);
        graphics.fill(x, y + h - c, x + t, y + h, FAIL_BORDER);
        graphics.fill(x + w - c, y + h - t, x + w, y + h, FAIL_BORDER);
        graphics.fill(x + w - t, y + h - c, x + w, y + h, FAIL_BORDER);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
