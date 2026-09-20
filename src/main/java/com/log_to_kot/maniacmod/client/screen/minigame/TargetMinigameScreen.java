package com.log_to_kot.maniacmod.client.screen.minigame;

import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.minigame.TargetMinigameClickPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * "Generator Startup" — Hits: 0/N. Повзунок їздить вліво-вправо
 * рівномірно (детерміновано від {@code seed}, тому кожен запуск має
 * власну, але відтворювану на цьому клієнті траєкторію), гравець
 * клікає, щоб зупинити його на нерухомій цілі.
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

    private final long seed;
    private final double cursorSpeed;
    private final double hitZoneWidth;
    private final double targetPosition;
    private final int hitsRequired;
    private final long openedAtMillis;

    private int hits = 0;
    private boolean finished = false;

    public TargetMinigameScreen(long seed, double cursorSpeed, double hitZoneWidth,
                                 double targetPosition, int hitsRequired) {
        super(Component.translatable("maniacmod.minigame.target.title"));
        this.seed = seed;
        this.cursorSpeed = cursorSpeed;
        this.hitZoneWidth = hitZoneWidth;
        this.targetPosition = targetPosition;
        this.hitsRequired = hitsRequired;
        this.openedAtMillis = System.currentTimeMillis();
    }

    /** Поточна позиція повзунка (0.0-1.0) — трикутна хвиля туди-назад. */
    private double cursorPosition() {
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
    }

    /** Викликається ClientPacketHandler після RepairMinigameResultPacket — закриває екран у будь-якому разі. */
    public void onResult(boolean success) {
        if (finished) return;
        finished = true;
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (finished) return true;
        if (button == 0) {
            ModNetwork.toServer(new TargetMinigameClickPacket(cursorPosition()));
        }
        // Навмисно НЕ викликаємо super.mouseClicked — на цьому екрані
        // немає інших клікабельних віджетів, а клік завжди означає
        // "спробувати влучити", незалежно від того, де саме на екрані.
        // Повертаємо true (подія спожита) в обох випадках: false тут
        // означав би "нікого не цікавить цей клік", і Screen міг би
        // спробувати передати його кудись іще — цьому екрану це не
        // потрібно, клік завжди наш.
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

        ManiacUiTheme.drawPanel(graphics, panelX, panelY, PANEL_WIDTH, panelHeight);
        int contentY = ManiacUiTheme.drawTitle(graphics, font, title, panelX, panelY, PANEL_WIDTH);

        graphics.drawCenteredString(font,
            Component.translatable("maniacmod.minigame.target.hits", hits, hitsRequired),
            panelX + PANEL_WIDTH / 2, contentY, ManiacUiTheme.TEXT_BODY);

        int barX = panelX + (PANEL_WIDTH - BAR_WIDTH) / 2;
        int barY = contentY + font.lineHeight + 18;

        // Смуга-трек: сам ManiacUiTheme.drawProgressBar тут не підходить
        // (fraction-заливка зліва, а тут потрібен РУХОМИЙ повзунок
        // всередині статичного треку) — власна відмальовка того самого
        // стилю рамки.
        graphics.fill(barX, barY, barX + BAR_WIDTH, barY + BAR_HEIGHT, ManiacUiTheme.BAR_FRAME);
        graphics.fill(barX + 2, barY + 2, barX + BAR_WIDTH - 2, barY + BAR_HEIGHT - 2, ManiacUiTheme.BAR_BG);

        // Ціль — вертикальна риска, що трохи виступає за межі бару.
        int targetX = barX + (int) (targetPosition * BAR_WIDTH);
        graphics.fill(targetX - 1, barY - 4, targetX + 1, barY + BAR_HEIGHT + 4, 0xFFFFFFFF);

        // Зона влучання навколо цілі (тонка підказка складності).
        int zoneHalf = (int) (hitZoneWidth / 2.0 * BAR_WIDTH);
        graphics.fill(targetX - zoneHalf, barY + 2, targetX + zoneHalf, barY + BAR_HEIGHT - 2, 0x33FFFFFF);

        // Повзунок — світлий прямокутник, той самий колір, що
        // ManiacUiTheme.BAR_FILL_NEUTRAL, трохи більший за висоту бару.
        int cursorX = barX + (int) (cursorPosition() * BAR_WIDTH);
        graphics.fill(cursorX - 8, barY - 2, cursorX + 8, barY + BAR_HEIGHT + 2, ManiacUiTheme.BAR_FILL_NEUTRAL);
        ManiacUiTheme.border1px(graphics, cursorX - 8, barY - 2, 16, BAR_HEIGHT + 4, 0xFF2A2A2A);

        int hintY = panelY + panelHeight + HINT_GAP;
        ManiacUiTheme.drawHintBar(graphics, font,
            Component.translatable("maniacmod.minigame.stop_hint").getString(),
            panelX, hintY, PANEL_WIDTH, HINT_HEIGHT);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
