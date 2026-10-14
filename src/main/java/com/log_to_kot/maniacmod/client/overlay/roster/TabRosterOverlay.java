package com.log_to_kot.maniacmod.client.overlay.roster;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.ManiacTabTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import dev.shaurmalib.forge.tab.TabListStyle;
import dev.shaurmalib.forge.tab.TabVisibilityModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.UUID;

/**
 * Tab-екран мода: утримання ванільної клавіші Tab (через
 * {@link TabVisibilityModule}, підключений у {@code ClientSetup}) показує
 * ПРИЛАДОВУ ПАНЕЛЬ МАТЧУ замість ванільного player_list.
 *
 * ── Що на екрані ─────────────────────────────────────────────────────
 *   ┌────────────────────────── МАНЬЯК ───────────────────────────┐
 *   │ [ 3D генератор ] ЗАВДАННЯ · назва · 2/5 · слоти · віхи     │ ← {@link TabObjectiveCard}
 *   ├───────────────┬──────────────────────────────────────────────┤
 *   │ МАНЬЯК (3D)   │ ВИЖИВШІ   ▸ картки з хп/таймером            │ ← {@link TabManiacCard}
 *   ├───────────────┤ ВИБУЛИ    ▸ стислі рядки                     │   {@link TabSurvivorList}
 *   │ ТИ (3D) / ЗВЕД│ ГЛЯДАЧІ                                      │ ← {@link TabSideCards}
 *   └───────────────┴──────────────────────────────────────────────┘
 * У лобі ролей іще нема: зверху лише онлайн-лічильник, ліворуч — «ТИ»,
 * праворуч — усі гравці.
 *
 * ── Джерело даних ────────────────────────────────────────────────────
 * Виключно {@link ClientMatchState} ({@code roster()} — хто є хто,
 * {@code objectives()} — що робимо, {@code downedEntries()} — таймери
 * лежачих). Клас НІЧОГО не рахує сам: лише розкладає готове по
 * карточках ({@link TabContext}).
 *
 * ── Хореографія (секунди від натискання Tab) ─────────────────────────
 *   0.00  світла лінія розтягується по центру екрана
 *   0.12  панель розгортається вертикально («вмикання CRT»)
 *   0.20  іскри по краях (без звуку)
 *   0.45  назви «розшифровуються» з шуму, картки в'їжджають по черзі
 *   0.55  слоти генераторів наливаються до реального прогресу
 *   0.50  3D-моделі стають видимими й починають обертатись
 * Закриття — зворотне: панель схлопується в лінію за {@code progress}
 * (його веде {@link TabVisibilityModule}, ~0.1 с).
 *
 * ── 3D ───────────────────────────────────────────────────────────────
 * Моделі малюються лише коли панель розгорнута ПОВНІСТЮ: під час
 * масштабування сутність спотворилась би, а її прозорість не підкоряється
 * множнику панелі. Див. {@link TabModel3d}.
 *
 * ── Стиль ────────────────────────────────────────────────────────────
 * Вигляд — {@link ManiacTabTheme}, підключена до бібліотечного
 * {@link TabListStyle} другим аргументом конструктора (shaurma-lib
 * тепер підтримує теми, див. {@code dev.shaurmalib.forge.tab.TabTheme}).
 *
 * ── Instance, не static ──────────────────────────────────────────────
 * Анімаційний стан (слоти, рядки, іскри) живе між кадрами — той самий
 * патерн, що {@code ManiacHotbarOverlay}.
 */
public final class TabRosterOverlay implements TabVisibilityModule.TabRenderer {

    private static final int PANEL_W = 440;
    private static final int PAD = 10;
    private static final int LEFT_W = 140;
    private static final int COL_GAP = 6;
    private static final int GAP = 6;
    private static final int FOOTER_H = 14;
    private static final int LEFT_MIN_H = 196;
    private static final int LOBBY_LEFT_MIN_H = 120;
    private static final int MANIAC_CARD_H = 118;
    /** Висота лівої колонки, коли картки маньяка нема (див. TabContext#maniacHidden). */
    private static final int HIDDEN_LEFT_MIN_H = 100;

    private final ManiacTabTheme theme = new ManiacTabTheme();
    private final TabListStyle style = new TabListStyle(this::resolveSkin, theme);

    private final TabContext ctx = new TabContext();
    private final TabObjectiveCard objectiveCard = new TabObjectiveCard();
    private final TabManiacCard maniacCard = new TabManiacCard();
    private final TabSideCards sideCards = new TabSideCards();
    private final TabSurvivorList survivorList = new TabSurvivorList();
    private final UiFx.Sparks sparks = new UiFx.Sparks(160);

    private final long[] frameClock = new long[1];
    private long openedAtMs = 0L;
    private boolean introSparksDone = false;

    // ── Життєвий цикл (викликає TabVisibilityModule) ─────────────────────

    @Override
    public void onOpen() {
        openedAtMs = System.currentTimeMillis();
        introSparksDone = false;
        frameClock[0] = 0L;
        sparks.clear();
        objectiveCard.onOpen(ClientMatchState.objectives());
        maniacCard.onOpen();
        sideCards.onOpen();
        survivorList.onOpen();
        // Звуків появи/закриття немає навмисно: Tab відкривається безшумно.
    }

    @Override
    public void onClose() {
        // Безшумно (див. onOpen).
    }

    // ── Кадр ─────────────────────────────────────────────────────────────

    @Override
    public void render(net.minecraftforge.client.gui.overlay.ForgeGui gui, GuiGraphics g,
                       float partialTick, int screenWidth, int screenHeight, float progress) {
        style.tick();
        Minecraft mc = Minecraft.getInstance();
        boolean open = TabVisibilityModule.isOpen();

        // ── збір контексту кадру ──
        TabContext c = ctx;
        c.g = g;
        c.font = mc.font;
        c.style = style;
        c.theme = theme;
        c.sparks = sparks;
        c.nowMs = System.currentTimeMillis();
        c.t = open ? Math.max(0f, (c.nowMs - openedAtMs) / 1000f) : Math.max(c.t, 2f);
        c.dt = UiAnim.frameDt(frameClock);
        c.me = myUuid();
        c.myRole = ClientMatchState.role();
        c.phase = ClientMatchState.phase();
        c.lobby = c.phase == GamePhase.LOBBY;
        c.objectives = ClientMatchState.objectives();
        c.partition(ClientMatchState.roster());

        // ── розкладка ──
        final int innerW = PANEL_W - PAD * 2;
        final int listW = innerW - LEFT_W - COL_GAP;
        int listH = survivorList.measure(c);
        // Без картки маньяка (секрет для не-маньяків) ліва колонка — лише одна картка, тож нижчa.
        int leftMin = c.lobby ? LOBBY_LEFT_MIN_H : c.maniacHidden ? HIDDEN_LEFT_MIN_H : LEFT_MIN_H;
        int bodyH = Math.max(leftMin, listH);
        int objH = c.lobby ? 14 : TabObjectiveCard.HEIGHT + GAP;
        int panelH = theme.titleHeight() + objH + bodyH + GAP + FOOTER_H;

        float fit = Math.min(1f, (screenHeight - 12f) / panelH);
        float cxScreen = screenWidth / 2f;
        float cyScreen = screenHeight / 2f;
        int panelX = Math.round(cxScreen - PANEL_W / 2f);
        int panelY = Math.round(cyScreen - panelH / 2f);

        // ── вступ/закриття: лінія → розгортання вертикально ──
        float lineP = open ? TabFx.stage(c.t, 0f, 0.12f) : 1f;
        float grow = open ? TabFx.stageOut(c.t, 0.10f, 0.42f) : UiAnim.easeInOutCubic(UiAnim.clamp01(progress));
        if (open && c.t >= 0.52f) grow = 1f;
        float scaleY = Math.max(0.012f, grow);
        boolean fullyOpen = open && grow >= 0.999f && progress >= 0.999f;
        c.model3d = fullyOpen && c.t > 0.5f && fit >= 0.999f;
        // fit < 1 — панель не влізає по висоті й стиснута: 3D тоді теж вимикаємо
        // (моделі в такому масштабі й так не читаються, а ризик спотворень зайвий).

        var pose = g.pose();
        pose.pushPose();
        pose.translate(cxScreen, cyScreen, 0f);
        pose.scale(fit, fit * scaleY, 1f);
        pose.translate(-cxScreen, -cyScreen, 0f);

        if (grow < 0.06f) {
            drawLine(g, cxScreen, cyScreen, lineP, progress);
        } else {
            drawPanelContents(c, panelX, panelY, innerW, listW, listH, bodyH, objH, panelH, progress, fullyOpen);
        }
        pose.popPose();

        // Іскри — у координатах екрана, поверх усього.
        if (fullyOpen) {
            if (!introSparksDone && c.t > 0.5f) {
                introSparksDone = true;
                emitIntroSparks(panelX, panelY, panelH, fit, cyScreen);
            }
            sparks.tickAndRender(g, c.dt, 140f);
        }
    }

    // ── Панель ───────────────────────────────────────────────────────────

    private void drawPanelContents(TabContext c, int px, int py, int innerW, int listW,
                                   int listH, int bodyH, int objH, int panelH,
                                   float progress, boolean fullyOpen) {
        GuiGraphics g = c.g;
        float bgAnim = UiAnim.clamp01(progress * 1.4f);
        style.drawPanel(g, px, py, PANEL_W, panelH, bgAnim);

        int cx = px + PANEL_W / 2;
        style.drawHeader(g, c.font, cx, py, bgAnim,
            "maniacmod.tab.title.mode", c.phase.translationKey());

        int x0 = px + PAD;
        int y = py + theme.titleHeight();

        if (c.lobby) {
            drawOnline(c, cx, y + 1);
            y += objH;
        } else {
            objectiveCard.draw(c, x0, y, innerW);
            y += objH;
        }

        // ── ліва колонка ──
        int leftX = x0;
        int listX = x0 + LEFT_W + COL_GAP;
        if (c.lobby) {
            sideCards.drawSelf(c, leftX, y, LEFT_W, bodyH, true);
        } else {
            // Картку маньяка бачить лише сам маньяк; для решти її взагалі немає — не показуємо навіть «?».
            int secondY = y;
            int secondH = bodyH;
            if (!c.maniacHidden) {
                maniacCard.draw(c, leftX, y, LEFT_W, MANIAC_CARD_H);
                secondY = y + MANIAC_CARD_H + GAP - 2;
                secondH = bodyH - MANIAC_CARD_H - GAP + 2;
            }
            if (c.iAmSurvivor()) sideCards.drawSelf(c, leftX, secondY, LEFT_W, secondH, false);
            else sideCards.drawStats(c, leftX, secondY, LEFT_W, secondH);
        }

        // ── права колонка ──
        survivorList.draw(c, listX, y, listW);

        // ── футер ──
        int fy = y + bodyH + GAP;
        g.fill(x0, fy - 2, x0 + innerW, fy - 1, TabFx.a(ManiacTabTheme.CARD_EDGE, 1f));
        TabFx.text(g, c.font, Component.translatable("maniacmod.tab.brand").getString(),
            x0, fy + 2, 0.7f, TabFx.a(ManiacTabTheme.DIM, 0.8f));
        String right = Component.translatable("maniacmod.tab.footer", c.all.size()).getString();
        TabFx.textRight(g, c.font, right, x0 + innerW, fy + 2, 0.7f, TabFx.a(ManiacTabTheme.DIM, 0.8f));
    }

    private void drawOnline(TabContext c, int cx, int y) {
        float p = TabFx.stageOut(c.t, 0.4f, 0.3f);
        float pulse = TabFx.pulse(c.nowMs, 1400f);
        String s = Component.translatable("maniacmod.tab.online", c.all.size()).getString();
        int w = TabFx.width(c.font, s, 0.85f);
        c.g.fill(cx - w / 2 - 9, y + 4, cx - w / 2 - 5, y + 8, TabFx.a(ManiacTabTheme.OK, p * (0.6f + 0.4f * pulse)));
        TabFx.textCentered(c.g, c.font, s, cx + 2, y + 2, 0.85f, TabFx.a(ManiacTabTheme.OK, p));
    }

    /** Початкова «лінія вмикання»: розтягується від центру, світиться. */
    private void drawLine(GuiGraphics g, float cx, float cy, float lineP, float progress) {
        int half = Math.round(PANEL_W / 2f * UiAnim.easeOutCubic(lineP));
        int a = Math.round(255 * Math.max(0.35f, UiAnim.clamp01(progress * 2f)));
        int y = Math.round(cy);
        g.fill(Math.round(cx) - half, y - 1, Math.round(cx) + half, y + 1, (a << 24) | 0xF2F4F8);
        UiFx.glow(g, Math.round(cx) - half, y - 1, half * 2, 2, 0xC8232F, 0.7f, 4);
    }

    private void emitIntroSparks(int px, int py, int panelH, float fit, float cyScreen) {
        float top = cyScreen - panelH * fit / 2f;
        float left = px + 0f;
        float right = px + PANEL_W * 1f;
        sparks.burst(left, top, 14, 110f, -1f, 0.3f, 0.8f, 0xC2C9D6);
        sparks.burst(right, top, 14, 110f, 1f, 0.3f, 0.8f, 0xC2C9D6);
        sparks.burst(left, top, 8, 90f, -0.6f, -1f, 0.7f, 0xFF4B55);
        sparks.burst(right, top, 8, 90f, 0.6f, -1f, 0.7f, 0xFF4B55);
    }

    // ── Скін гравця ──────────────────────────────────────────────────────

    /**
     * Ванільний {@link PlayerInfo} з мережевого з'єднання — той самий шлях,
     * яким ванільний tab-список бере голови. {@code null}, якщо гравця ще
     * нема в трекері з'єднання: тема тоді малює темну заглушку.
     */
    private ResourceLocation resolveSkin(UUID uuid) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return null;
        PlayerInfo info = connection.getPlayerInfo(uuid);
        return info == null ? null : info.getSkinLocation();
    }

    private UUID myUuid() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? null : player.getUUID();
    }
}
