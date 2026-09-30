package com.log_to_kot.maniacmod.client.screen.statistics;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Підсумковий екран матчу: хто втік, хто загинув, ким був маньяк.
 *
 * ── Чому без нового мережевого пакета ─────────────────────────────────
 * {@link RosterSyncPacket} уже розсилається на КОЖЕН вхід у нову фазу
 * ({@code MatchOrchestrator.PhaseNetworkSync.onPhaseEnter}), а
 * {@code advanceTo(GamePhase.ENDING, ...)} відбувається рівно тоді,
 * коли {@code aliveSurvivorCount() == 0} — тобто кожен виживий уже
 * встиг стати ESCAPED або ELIMINATED (обидва стани прибирають гравця
 * з активних виживих). На момент показу цього екрана
 * {@link ClientMatchState#roster()} уже містить повний і остаточний
 * знімок матчу — заводити окремий {@code MatchResultPacket} для тих
 * самих даних означало б дублювати те, що вже приїхало.
 *
 * ── Коли показується і коли ховається ─────────────────────────────────
 * Відкривається з {@code ClientPacketHandler.onPhase(GamePhase.ENDING)}
 * через {@code Minecraft.getInstance().setScreen(new MatchResultScreen())}.
 * Сервер сам повертає всіх у {@code RESET} → {@code LOBBY} по завершенню
 * {@code endingTicks()} (див. {@code MatchOrchestrator.tickPhases}); цей
 * клас на це окремо не реагує — {@code ClientMatchState.setPhase(LOBBY)}
 * і так скидає {@code roster}, а сам екран гравець закриває кнопкою або
 * ванільним Escape (стандартна поведінка {@link Screen}, нічого
 * перевизначати не треба).
 */
public final class MatchResultScreen extends Screen {

    private static final int PANEL_WIDTH = 320;
    private static final int ROW_HEIGHT = 14;
    private static final int SECTION_GAP = 10;

    public MatchResultScreen() {
        super(Component.translatable("maniacmod.result.title"));
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                button -> onClose())
            .bounds(width / 2 - 50, height - 30, 100, 20)
            .build());
    }

    private long openedAtMs;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (openedAtMs == 0L) openedAtMs = System.currentTimeMillis();
        long now = System.currentTimeMillis();
        float openT = UiAnim.clamp01((now - openedAtMs) / 420f);

        // Фон: плавне затемнення замість ванільної плашки.
        graphics.fill(0, 0, width, height, GeneratorFuelUiTheme.alpha(0xFF000000, 0.62f * UiAnim.easeOutCubic(openT)));

        List<RosterSyncPacket.RosterEntry> roster = ClientMatchState.roster();
        List<RosterSyncPacket.RosterEntry> escaped = filterByState(roster, SurvivorState.ESCAPED);
        List<RosterSyncPacket.RosterEntry> eliminated = filterByState(roster, SurvivorState.ELIMINATED);
        String maniacName = maniacDisplayName(roster);

        int rows = Math.max(1, escaped.size()) + Math.max(1, eliminated.size());
        int panelH = 24 + 16 + 22 + rows * ROW_HEIGHT + 2 * (ROW_HEIGHT + 6) + SECTION_GAP + 16;
        int panelX = width / 2 - PANEL_WIDTH / 2;
        int panelY = Math.max(16, (height - 50 - panelH) / 2);

        // Панель «висувається» знизу з м'яким масштабом (як банер генератора).
        float ease = UiAnim.easeOutBack(openT, 0.6f);
        graphics.pose().pushPose();
        float cx = width / 2f, cy = panelY + panelH / 2f;
        graphics.pose().translate(cx, cy + (1f - ease) * 18f, 0f);
        float sc = 0.90f + 0.10f * ease;
        graphics.pose().scale(sc, sc, 1f);
        graphics.pose().translate(-cx, -cy, 0f);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, UiAnim.clamp01(openT * 1.5f));

        ManiacUiTheme.drawPanel(graphics, panelX, panelY, PANEL_WIDTH, panelH, true);
        if (openT < 1f) {
            UiFx.scanBeam(graphics, panelX + 1, panelY + 1, PANEL_WIDTH - 2, panelH - 2, openT, 28, 0xFFFFFF, 50);
        }

        int y = ManiacUiTheme.drawTitle(graphics, font, title, panelX, panelY, PANEL_WIDTH);
        graphics.drawString(font, Component.translatable("maniacmod.result.maniac", maniacName)
            .getString(), panelX + 16, y, GeneratorFuelUiTheme.FILL_WARN, false);
        y += SECTION_GAP + 8;

        int[] rowCounter = {0};
        y = renderSection(graphics, panelX + 16, y, now,
            Component.translatable("maniacmod.result.escaped"), escaped, GeneratorFuelUiTheme.FILL, rowCounter);
        y += SECTION_GAP;
        renderSection(graphics, panelX + 16, y, now,
            Component.translatable("maniacmod.result.eliminated"), eliminated, GeneratorFuelUiTheme.FILL_DANGER, rowCounter);

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        graphics.pose().popPose();

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** Секція: заголовок з роздільником + рядки, що по черзі «в'їжджають» зліва. */
    private int renderSection(GuiGraphics graphics, int x, int y, long now,
                               Component sectionTitle, List<RosterSyncPacket.RosterEntry> entries,
                               int color, int[] rowCounter) {
        graphics.drawString(font, sectionTitle.getString().toUpperCase(java.util.Locale.ROOT), x, y,
            ManiacUiTheme.TEXT_BODY, false);
        y += ROW_HEIGHT - 2;
        ManiacUiTheme.divider(graphics, x, y, PANEL_WIDTH - 32);
        y += 6;

        if (entries.isEmpty()) {
            graphics.drawString(font, Component.translatable("maniacmod.result.none"), x + 8, y,
                ManiacUiTheme.TEXT_DIM, false);
            return y + ROW_HEIGHT;
        }

        for (RosterSyncPacket.RosterEntry entry : entries) {
            float rowT = UiAnim.easeOutCubic(UiAnim.clamp01(
                (now - openedAtMs - 260L - rowCounter[0] * 90L) / 320f));
            rowCounter[0]++;
            int slideX = Math.round((1f - rowT) * -24f);
            int a = Math.round(255 * rowT);
            // Маркер стану + ім'я; блимаючий акцент-квадрат зліва.
            float blink = 0.6f + 0.4f * (float) Math.sin(now / 380.0 + rowCounter[0]);
            graphics.fill(x + slideX, y + 2, x + slideX + 3, y + 7,
                GeneratorFuelUiTheme.alpha(0xFF000000 | (color & 0xFFFFFF), rowT * blink));
            // Альфа < 4 у ванільному drawString трактується як непрозоре — пропускаємо кадр.
            if (a >= 8) {
                graphics.drawString(font, entry.displayName(), x + 8 + slideX, y,
                    (a << 24) | (GeneratorFuelUiTheme.TEXT & 0xFFFFFF), false);
            }
            y += ROW_HEIGHT;
        }
        return y;
    }

    private List<RosterSyncPacket.RosterEntry> filterByState(List<RosterSyncPacket.RosterEntry> roster,
                                                               SurvivorState state) {
        List<RosterSyncPacket.RosterEntry> result = new ArrayList<>();
        for (RosterSyncPacket.RosterEntry entry : roster) {
            if (entry.role() == RoleSyncPacket.Role.SURVIVOR && entry.state() == state) result.add(entry);
        }
        return result;
    }

    /** Те саме "не звалити рендер через один невідомий archetypeId", що в TabRosterOverlay. */
    private String maniacDisplayName(List<RosterSyncPacket.RosterEntry> roster) {
        for (RosterSyncPacket.RosterEntry entry : roster) {
            if (entry.role() != RoleSyncPacket.Role.MANIAC) continue;
            if (entry.archetypeId() == null || entry.archetypeId().isEmpty()) return entry.displayName();
            try {
                ManiacArchetype archetype = ManiacRegistry.get(entry.archetypeId());
                return entry.displayName() + " (" + archetype.displayName() + ")";
            } catch (IllegalArgumentException unknownArchetype) {
                return entry.displayName();
            }
        }
        return "?";
    }

    /** Підсумковий екран не ставить гру на паузу — інші виживі й далі бачать результат наживо, поки хтось читає свій. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
