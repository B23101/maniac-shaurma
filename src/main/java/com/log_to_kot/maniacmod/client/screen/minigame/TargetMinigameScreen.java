package com.log_to_kot.maniacmod.client.screen.minigame;

import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.minigame.TargetMinigameClickPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "Generator Startup" — Hits: 0/N. Тонка ПАЛКА-повзунок їздить
 * вліво-вправо рівномірно, гравець натискає ПРОБІЛ, щоб зупинити її
 * всередині нерухомого ПРЯМОКУТНИКА-цілі (це і є зона влучання —
 * ширина прямокутника дорівнює {@code hitZoneWidth}, тож те, що
 * гравець бачить, збігається з тим, що перевіряє сервер).
 *
 * ── Промах ───────────────────────────────────────────────────────────
 * Промах фарбує все вікно в червоне. Червоним воно лишається до
 * закриття: екран закриває серверний {@code RepairMinigameResultPacket},
 * а щоб червоний стан встигав побачити, клієнт тримає екран відкритим
 * {@link #FAIL_LINGER_MS} після вердикту (див. {@link #onResult}).
 *
 * Вигляд — {@link ManiacUiTheme}: темна панель з кутовими акцентами,
 * заголовок капсом + роздільник, лічильник попадань, смуга-повзунок,
 * окрема панель-підказка знизу ("Пробел — остановить"). 1:1 референс
 * "Вирватись із пастки" — та сама панель обслуговує обидва текстові
 * контексти (втеча з пастки МАНЬЯКА і запуск генератора), різниця
 * лише в {@code Component}-перекладах, що приходять у конструктор.
 *
 * ── Хто що рахує ─────────────────────────────────────────────────────
 * Позиція повзунка — ЛОКАЛЬНИЙ клієнтський розрахунок від
 * {@link #openedAtMillis} і {@link #cursorSpeed} (трикутна хвиля:
 * туди-назад між 0 і 1). {@code seed} зберігається на випадок, якщо
 * рух ускладниться (наприклад випадковими змінами швидкості) і йому
 * знадобиться детермінований, а не суто часовий розрахунок — поки що
 * сама лише трикутна хвиля симетрична й seed не читає. На клік клієнт лише повідомляє серверу, де,
 * на його думку, зараз стоїть повзунок ({@link TargetMinigameClickPacket})
 * — сервер сам вирішує, влучання це чи ні, і присилає
 * {@code RepairMinigameProgressPacket}/{@code RepairMinigameResultPacket}
 * у відповідь. Тому цей екран НІКОЛИ сам не малює "влучив!" — лише
 * реагує на те, що прийшло з сервера ({@link #onHit}/{@link #onResult}).
 *
 * ── Чому не закривається по ESC ──────────────────────────────────────
 * За дизайном гравець не може просто вийти з міні-гри — вихід
 * трактується як провал (перевіряється й караеться СЕРВЕРОМ через
 * дистанцію до генератора в {@code GeneratorModule.tickMinigameTimeouts},
 * а не цим екраном). {@link #shouldCloseOnEsc()} тому явно false, а
 * {@link #onClose()} нічого не робить — закриває екран лише виклик
 * {@link #forceClose()} з {@code ClientPacketHandler} у відповідь на
 * серверний результат.
 */
public final class TargetMinigameScreen extends Screen {

    private static final int PANEL_WIDTH = 280;
    private static final int BAR_WIDTH = 240;
    private static final int BAR_HEIGHT = 18;
    private static final int HINT_HEIGHT = 26;
    private static final int HINT_GAP = 10;

    /** Товщина палки-повзунка, px. Тонка — щоб було видно, що вона «стоїть» у прямокутнику. */
    private static final int STICK_WIDTH = 3;

    /** Наскільки палка виступає над/під смугою, px. */
    private static final int STICK_OVERSHOOT = 5;

    /**
     * Скільки мс екран лишається відкритим після провалу, щоб червоний
     * стан було видно. Сервер закриває гру одразу на промах, тож без
     * цього вікно зникало б раніше, ніж гравець встигне його помітити.
     */
    private static final long FAIL_LINGER_MS = 900;

    /** Червоні відтінки провалу (ARGB). */
    private static final int FAIL_PANEL_BG = 0xE63A0A0A;
    private static final int FAIL_BORDER = 0xFFB0242F;
    private static final int FAIL_BAR_BG = 0xFF2A0C0E;

    private final long seed;
    private final double cursorSpeed;
    private final double hitZoneWidth;
    private final double targetPosition;
    private final int hitsRequired;
    private final long openedAtMillis;

    private int hits = 0;
    private boolean finished = false;

    /** true, щойно гравець промахнувся — вікно червоніє до закриття. */
    private boolean failed = false;

    /** Коли (мс) закривати екран після провалу; 0 — закриття не заплановане. */
    private long closeAtMillis = 0;

    /**
     * Позиція палки, на якій її ЗАМОРОЖЕНО в момент натискання. Після
     * провалу палка мусить лишитись там, де промахнулась, — інакше
     * гравець не бачив би, наскільки схибив.
     */
    private double frozenCursor = -1;

    /**
     * Пробіл затиснуто й ще не відпущено після попередньої спроби.
     * Початкове значення береться з РЕАЛЬНОГО стану клавіші в момент
     * відкриття ({@code keyJump.isDown()}): гравець, що ще тримає пробіл
     * (стрибав, коли випала міні-гра), не має отримати «безкоштовну»
     * першу спробу — спершу відпустить. Жорстке {@code true} було б
     * гірше: якщо пробіл НЕ тримали, {@code keyReleased} ніколи б не
     * прийшов, і гравець не міг би зробити жодної спроби взагалі.
     */
    private boolean spaceHeld;

    /**
     * @param titleKey      ключ локалізації заголовка. Екран ОДИН на дві
     *                      міні-гри (ремонт генератора й визволення з
     *                      капкана — та сама механіка), але заголовок у
     *                      них різний, тож його вибирає той, хто відкриває
     *                      екран ({@code ClientPacketHandler}) за своїм
     *                      станом, а не сама механіка.
     */
    public TargetMinigameScreen(String titleKey, long seed, double cursorSpeed, double hitZoneWidth,
                                 double targetPosition, int hitsRequired) {
        super(Component.translatable(titleKey));
        this.seed = seed;
        this.cursorSpeed = cursorSpeed;
        this.hitZoneWidth = hitZoneWidth;
        this.targetPosition = targetPosition;
        this.hitsRequired = hitsRequired;
        this.openedAtMillis = System.currentTimeMillis();
        // Прямо GLFW, а не keyJump: keyPressed нижче ловить фізичний пробіл,
        // і початковий стан мусить відповідати ТІЙ САМІЙ клавіші (гравець
        // міг перепризначити «стрибок» на іншу).
        this.spaceHeld = org.lwjgl.glfw.GLFW.glfwGetKey(
            Minecraft.getInstance().getWindow().getWindow(),
            org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    /** Поточна позиція повзунка (0.0-1.0) — трикутна хвиля туди-назад. */
    private double cursorPosition() {
        // Після провалу палка стоїть там, де промахнулась.
        if (frozenCursor >= 0) return frozenCursor;
        double elapsedSeconds = (System.currentTimeMillis() - openedAtMillis) / 1000.0;
        // Спільна з сервером формула — сервер перевіряє клік нею ж.
        return com.log_to_kot.maniacmod.map.minigame.TargetMinigameSpec.cursorAt(elapsedSeconds, cursorSpeed);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        // Навмисно порожньо — див. клас-докстрінг "Чому не закривається по ESC".
    }

    /** Викликається ClientPacketHandler після RepairMinigameProgressPacket.targetHit(...). */
    public void onHit(int hitsSoFar) {
        this.hits = hitsSoFar;
        // Влучання: палка знову їде — наступна спроба з чистого аркуша.
        // Відлік часу НЕ скидаємо: сервер перевіряє позицію за
        // годинником від відкриття гри, і зсув розійшовся б із ним.
        this.frozenCursor = -1;
    }

    /**
     * Викликається ClientPacketHandler після RepairMinigameResultPacket.
     *
     * Успіх закриває екран одразу. Провал НЕ закриває його негайно:
     * вікно червоніє й висить {@link #FAIL_LINGER_MS}, щоб гравець
     * побачив, що схибив; фактичне закриття робить {@link #tick()}.
     */
    public void onResult(boolean success) {
        if (finished) return;
        if (success) {
            finished = true;
            Minecraft.getInstance().setScreen(null);
            return;
        }
        // Провал приходить і від промаху, і від виходу за дистанцію чи
        // кінця фази — в обох випадках гравцю показуємо червоне вікно,
        // а не глухо зникаємо. Сервер гру вже закрив: далі натискання
        // нікуди не йдуть, тому finished = true.
        markFailed();
        finished = true;
    }

    /** Вмикає червоний стан і планує закриття. Ідемпотентно. */
    private void markFailed() {
        if (failed) return;
        failed = true;
        if (frozenCursor < 0) frozenCursor = cursorPosition();
        closeAtMillis = System.currentTimeMillis() + FAIL_LINGER_MS;
    }

    @Override
    public void tick() {
        super.tick();
        if (closeAtMillis != 0 && System.currentTimeMillis() >= closeAtMillis) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    /**
     * ПРОБІЛ — зупинити палку. Раніше це була ЛКМ; пробіл зручніший
     * (не треба цілитись мишею й тримати її над панеллю).
     *
     * Автоповтор: якщо пробіл затиснуто, GLFW шле {@code keyPressed}
     * повторно, і Screen.keyPressed не відрізняє повтор від першого
     * натискання. Тому {@link #spaceHeld} вимагає спершу ВІДПУСТИТИ
     * пробіл ({@link #keyReleased}) — лише свідоме натискання рахується
     * спробою. Клієнт лише повідомляє серверу позицію палки — вердикт
     * виносить сервер (див. клас-докстрінг).
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
            if (!spaceHeld && !finished && !failed) {
                spaceHeld = true;
                double position = cursorPosition();
                // Локально «заморожуємо» палку одразу: якщо це промах,
                // вона лишиться на місці схибленого натискання. Якщо влучання —
                // сервер підтвердить onHit(), і ми розморозимо (див. onHit).
                frozenCursor = position;
                ModNetwork.toServer(new TargetMinigameClickPacket(position));
            }
            return true;
        }
        // ESC та решта клавіш навмисно ігноруються — вихід із міні-гри = провал.
        return true;
    }

    /** Відпускання пробілу знову дозволяє наступну спробу. */
    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
            spaceHeld = false;
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    /**
     * Миша на цьому екрані нічого не робить: керування — пробілом.
     * Клік споживаємо, щоб він не «проваливався» в ігровий світ під панеллю.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Навмисно НЕ renderBackground(graphics) — референс показує
        // ігровий світ ПОЗАДУ панелі (затемнений, але видимий), а не
        // суцільну ванільну заглушку на весь екран.
        int panelHeight = 150;
        int panelX = width / 2 - PANEL_WIDTH / 2;
        int panelY = height / 2 - panelHeight / 2 - (HINT_HEIGHT + HINT_GAP) / 2;

        drawPanelFrame(graphics, panelX, panelY, PANEL_WIDTH, panelHeight);
        int contentY = ManiacUiTheme.drawTitle(graphics, font, title, panelX, panelY, PANEL_WIDTH);

        graphics.drawCenteredString(font,
            Component.translatable("maniacmod.minigame.target.hits", hits, hitsRequired),
            panelX + PANEL_WIDTH / 2, contentY, failed ? FAIL_BORDER : ManiacUiTheme.TEXT_BODY);

        int barX = panelX + (PANEL_WIDTH - BAR_WIDTH) / 2;
        int barY = contentY + font.lineHeight + 18;

        // Смуга-трек: рамка + фон. У провалі — червоні.
        graphics.fill(barX, barY, barX + BAR_WIDTH, barY + BAR_HEIGHT,
            failed ? FAIL_BORDER : ManiacUiTheme.BAR_FRAME);
        graphics.fill(barX + 2, barY + 2, barX + BAR_WIDTH - 2, barY + BAR_HEIGHT - 2,
            failed ? FAIL_BAR_BG : ManiacUiTheme.BAR_BG);

        // ЦІЛЬ — прямокутник. Його ширина дорівнює hitZoneWidth, тобто
        // рівно тій зоні, яку перевіряє сервер (TargetMinigameSpec.isHit:
        // |cursor − target| ≤ hitZoneWidth/2). Раніше це була тонка риска
        // з напівпрозорим «підказковим» ореолом навколо, і гравець
        // цілився в риску, хоча влучити можна було й поруч.
        int targetCenterX = barX + (int) Math.round(targetPosition * BAR_WIDTH);
        int zoneHalf = Math.max(1, (int) Math.round(hitZoneWidth / 2.0 * BAR_WIDTH));
        int zoneLeft = Math.max(barX + 2, targetCenterX - zoneHalf);
        int zoneRight = Math.min(barX + BAR_WIDTH - 2, targetCenterX + zoneHalf);
        int zoneFill = failed ? 0xFF6A1A20 : 0xFF3F7F4A;
        int zoneEdge = failed ? FAIL_BORDER : 0xFF7FD68D;
        graphics.fill(zoneLeft, barY + 2, zoneRight, barY + BAR_HEIGHT - 2, zoneFill);
        ManiacUiTheme.border1px(graphics, zoneLeft, barY + 2, zoneRight - zoneLeft, BAR_HEIGHT - 4, zoneEdge);

        // ПАЛКА — тонка вертикальна риска, що виступає за смугу.
        int stickCenterX = barX + (int) Math.round(cursorPosition() * BAR_WIDTH);
        int stickLeft = stickCenterX - STICK_WIDTH / 2;
        graphics.fill(stickLeft, barY - STICK_OVERSHOOT,
            stickLeft + STICK_WIDTH, barY + BAR_HEIGHT + STICK_OVERSHOOT,
            failed ? 0xFFFFFFFF : ManiacUiTheme.BAR_FILL_NEUTRAL);
        ManiacUiTheme.border1px(graphics, stickLeft - 1, barY - STICK_OVERSHOOT - 1,
            STICK_WIDTH + 2, BAR_HEIGHT + STICK_OVERSHOOT * 2 + 2, 0xFF2A2A2A);

        int hintY = panelY + panelHeight + HINT_GAP;
        drawHintFrame(graphics, panelX, hintY, PANEL_WIDTH, HINT_HEIGHT);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Панель міні-гри; у провалі — червоний фон і рамка замість стандартних. */
    private void drawPanelFrame(GuiGraphics graphics, int x, int y, int w, int h) {
        if (!failed) {
            ManiacUiTheme.drawPanel(graphics, x, y, w, h);
            return;
        }
        graphics.fill(x, y, x + w, y + h, FAIL_PANEL_BG);
        ManiacUiTheme.border1px(graphics, x, y, w, h, FAIL_BORDER);
        // Кутові акценти — як у стандартної панелі, але червоні.
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

    /** Панель-підказка: «Пробіл — зупинити»; у провалі теж червона. */
    private void drawHintFrame(GuiGraphics graphics, int x, int y, int w, int h) {
        String text = Component.translatable("maniacmod.minigame.stop_hint").getString();
        if (!failed) {
            ManiacUiTheme.drawHintBar(graphics, font, text, x, y, w, h);
            return;
        }
        graphics.fill(x, y, x + w, y + h, FAIL_PANEL_BG);
        ManiacUiTheme.border1px(graphics, x, y, w, h, FAIL_BORDER);
        graphics.drawCenteredString(font, text, x + w / 2, y + (h - font.lineHeight) / 2, FAIL_BORDER);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
