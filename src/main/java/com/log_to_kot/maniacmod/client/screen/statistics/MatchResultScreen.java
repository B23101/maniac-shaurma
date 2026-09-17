package com.log_to_kot.maniacmod.client.screen.statistics;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        List<RosterSyncPacket.RosterEntry> roster = ClientMatchState.roster();
        List<RosterSyncPacket.RosterEntry> escaped = filterByState(roster, SurvivorState.ESCAPED);
        List<RosterSyncPacket.RosterEntry> eliminated = filterByState(roster, SurvivorState.ELIMINATED);
        String maniacName = maniacDisplayName(roster);

        int x = width / 2 - PANEL_WIDTH / 2;
        int y = 30;

        graphics.drawCenteredString(font, title, width / 2, y, 0xFFFFFF);
        y += 24;

        graphics.drawString(font,
            Component.translatable("maniacmod.result.maniac", maniacName), x, y, 0xFFAA00, false);
        y += SECTION_GAP * 2;

        y = renderSection(graphics, x, y,
            Component.translatable("maniacmod.result.escaped"), escaped, 0x55FF55);
        y += SECTION_GAP;
        renderSection(graphics, x, y,
            Component.translatable("maniacmod.result.eliminated"), eliminated, 0xFF5555);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** @return Y одразу під намальованою секцією — для наступної секції нижче. */
    private int renderSection(GuiGraphics graphics, int x, int y,
                               Component sectionTitle, List<RosterSyncPacket.RosterEntry> entries, int color) {
        graphics.drawString(font, sectionTitle, x, y, 0xAAAAAA, false);
        y += ROW_HEIGHT;

        if (entries.isEmpty()) {
            graphics.drawString(font, Component.translatable("maniacmod.result.none"), x + 6, y, 0x777777, false);
            return y + ROW_HEIGHT;
        }

        for (RosterSyncPacket.RosterEntry entry : entries) {
            graphics.drawString(font, entry.displayName(), x + 6, y, color, false);
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
