package com.log_to_kot.maniacmod.client.overlay.roster;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import dev.shaurmalib.common.tab.TabCell;
import dev.shaurmalib.common.tab.TabColumn;
import dev.shaurmalib.common.tab.TabRow;
import dev.shaurmalib.forge.tab.TabListStyle;
import dev.shaurmalib.forge.tab.TabVisibilityModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tab-екран мода: утримання ванільної клавіші Tab (через
 * {@link TabVisibilityModule}, підключений у
 * {@code ClientSetup.onClientSetup}) показує список УСІХ гравців
 * матчу замість ванільного player_list.
 *
 * ── Джерело даних ────────────────────────────────────────────────────
 * Єдине джерело — {@link ClientMatchState#roster()}, що наповнюється з
 * {@link com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket}.
 * Цей клас нічого не рахує сам (як і решта оверлеїв мода) — лише
 * розкладає вже готові {@code RosterEntry} в рядки {@link TabRow}.
 *
 * ── Один рушій рендеру на всі три ролі ────────────────────────────────
 * Маньяк / виживий / глядач — не три окремі layout-и, а один список,
 * відсортований так, щоб маньяк був згори, потім живі виживі (за
 * спаданням хп), потім ті, хто вже вибув (сірим, перекреслено), потім
 * глядачі. У {@code LOBBY} ролі ще не призначені (усі SPECTATOR за
 * {@code RosterSyncPacket} — {@code RoleSyncPacket} шлеться лише на
 * {@code ROLE_REVEAL}), тому в лобі всі рядки виглядають як звичайний
 * список "хто на сервері" без поділу на команди.
 *
 * ── Instance, не static ──────────────────────────────────────────────
 * {@link TabListStyle} тримає анімаційний стан (позиція/прозорість
 * рядків) між кадрами — той самий патерн, що {@code ManiacHotbarOverlay}
 * (instance, що реалізує контракт бібліотеки), а не статичний клас, як
 * прості оверлеї без анімаційної пам'яті ({@code SurvivorVitalsOverlay}).
 */
public final class TabRosterOverlay implements TabVisibilityModule.TabRenderer {

    private static final int PANEL_WIDTH = 300;
    private static final int ROW_WIDTH = 280;
    private static final int NAME_COLUMN_OFFSET = 0;
    private static final int STATUS_COLUMN_OFFSET = 190;

    private static final List<TabColumn> COLUMNS = List.of(
        new TabColumn("maniacmod.tab.column.name", NAME_COLUMN_OFFSET),
        new TabColumn("maniacmod.tab.column.status", STATUS_COLUMN_OFFSET)
    );

    private final TabListStyle style = new TabListStyle(this::resolveSkin);

    @Override
    public void render(net.minecraftforge.client.gui.overlay.ForgeGui gui,
                        net.minecraft.client.gui.GuiGraphics graphics,
                        float partialTick, int screenWidth, int screenHeight,
                        float progress) {
        style.tick();

        List<RosterSyncPacket.RosterEntry> entries = orderedEntries();
        style.pruneAnimState(entries.stream()
            .map(e -> e.playerId().toString())
            .collect(Collectors.toSet()));

        int rowCount = entries.size();
        int panelHeight = TabListStyle.TITLE_H + TabListStyle.HDR_H + rowCount * TabListStyle.ROW_H + 6;
        int panelX = (screenWidth - PANEL_WIDTH) / 2;
        int panelY = (screenHeight - panelHeight) / 2;

        style.drawPanel(graphics, panelX, panelY, PANEL_WIDTH, panelHeight, progress);

        int centerX = panelX + PANEL_WIDTH / 2;
        // Заголовок — назва режиму мода ("МАНЬЯК"), не фаза матчу:
        // у snipers_shaurma кожен окремий render*Tab-метод малював саме
        // назву свого режиму (SC/SD/SCN) у titleKey, а "Powered by
        // SHAURMA" (gui.snipers_shaurma.brand.sub — та сама фраза, той
        // самий бренд-підпис) завжди йшов другим рядком як subtitleKey.
        // Тут це було не перенесено: title показував лише "Лобі"/"Матч"
        // (без назви режиму), а subtitleKey передавався як null — тому
        // ні назва режиму, ні бренд-підпис узагалі не малювались.
        style.drawHeader(graphics, Minecraft.getInstance().font, centerX, panelY,
            progress, "maniacmod.tab.title.mode", "maniacmod.tab.brand");

        int columnsY = panelY + TabListStyle.TITLE_H;
        int rowX = panelX + (PANEL_WIDTH - ROW_WIDTH) / 2;
        style.drawColumnHeaderBg(graphics, rowX, columnsY, ROW_WIDTH, progress);
        style.drawColumns(graphics, Minecraft.getInstance().font, rowX, columnsY + 3, COLUMNS, progress);

        int rowsTop = columnsY + TabListStyle.HDR_H;
        UUID myId = myUuid();

        for (int i = 0; i < entries.size(); i++) {
            RosterSyncPacket.RosterEntry entry = entries.get(i);
            String animKey = entry.playerId().toString();

            float targetY = rowsTop + i * TabListStyle.ROW_H;
            style.syncRowY(animKey, targetY);
            int rowY = Math.round(style.getAnimY(animKey, targetY));

            boolean isMe = myId != null && entry.playerId().equals(myId);
            boolean isTerminal = entry.role() == RoleSyncPacket.Role.SURVIVOR && entry.state().isTerminal();
            boolean isLobby = ClientMatchState.phase() == GamePhase.LOBBY;
            boolean isSpectator = !isLobby
                && (entry.role() == RoleSyncPacket.Role.SPECTATOR || isTerminal);
            float rowAlpha = progress * style.syncOpacity(animKey, isSpectator);

            TabRow row = TabRow.builder(animKey)
                .skinUuid(entry.playerId())
                .isMe(isMe)
                .isSpectator(isSpectator)
                .isRespawning(entry.state() == SurvivorState.UNCONSCIOUS)
                .cell(new TabCell(entry.displayName(), nameColor(entry)))
                .cell(new TabCell(statusText(entry), statusColor(entry)))
                .build();

            int rankColor = nameColor(entry);
            String respawnLabel = Component.translatable("maniacmod.tab.status.unconscious").getString();

            style.drawRow(graphics, Minecraft.getInstance().font, rowX, rowY, ROW_WIDTH, TabListStyle.ROW_H,
                row, COLUMNS, i + 1, rankColor, 0, respawnLabel, progress, rowAlpha);
        }
    }

    // ── Порядок рядків ───────────────────────────────────────────────────

    /**
     * Маньяк згори, далі живі виживі за спаданням хп (щоб команда
     * одразу бачила, кому найгірше), далі ті, хто вже вибув
     * (втекли/загинули — не має значення хто раніше, стабільний
     * порядок за іменем), далі глядачі.
     */
    private List<RosterSyncPacket.RosterEntry> orderedEntries() {
        List<RosterSyncPacket.RosterEntry> all = new ArrayList<>(ClientMatchState.roster());
        all.sort((a, b) -> {
            int rankA = sortRank(a);
            int rankB = sortRank(b);
            if (rankA != rankB) return Integer.compare(rankA, rankB);
            if (rankA == 1) return Integer.compare(b.hp(), a.hp()); // живі виживі: більше хп — вище
            return a.displayName().compareToIgnoreCase(b.displayName());
        });
        return all;
    }

    private int sortRank(RosterSyncPacket.RosterEntry e) {
        if (e.role() == RoleSyncPacket.Role.MANIAC) return 0;
        if (e.role() == RoleSyncPacket.Role.SURVIVOR && !e.state().isTerminal()) return 1;
        if (e.role() == RoleSyncPacket.Role.SURVIVOR) return 2; // вибув (втік/загинув)
        return 3; // глядач
    }

    // ── Текст і колір ────────────────────────────────────────────────────

    private int nameColor(RosterSyncPacket.RosterEntry e) {
        return switch (e.role()) {
            case MANIAC -> TabListStyle.COL_RED;
            case SURVIVOR -> switch (e.state()) {
                case HEALTHY -> TabListStyle.COL_GREEN;
                case BROKEN_LEG, CRAWLING -> TabListStyle.COL_YELLOW;
                case UNCONSCIOUS -> TabListStyle.RESPAWN_ORANGE;
                case ELIMINATED, ESCAPED -> TabListStyle.COL_GRAY;
            };
            case SPECTATOR -> TabListStyle.COL_GRAY;
        };
    }

    /**
     * @return "42/100" для живого виживого; назва архетипу для маньяка;
     *         "Втік"/"Вибув" (переклад) для термінального стану;
     *         порожньо для глядача — там колонці статусу нема чого показати.
     */
    private String statusText(RosterSyncPacket.RosterEntry e) {
        return switch (e.role()) {
            case MANIAC -> maniacDisplayName(e.archetypeId());
            case SURVIVOR -> switch (e.state()) {
                case ESCAPED -> Component.translatable("maniacmod.tab.status.escaped").getString();
                case ELIMINATED -> Component.translatable("maniacmod.tab.status.eliminated").getString();
                case UNCONSCIOUS -> Component.translatable("maniacmod.tab.status.unconscious").getString();
                default -> e.hp() + "/" + e.maxHp();
            };
            case SPECTATOR -> "";
        };
    }

    private int statusColor(RosterSyncPacket.RosterEntry e) {
        if (e.role() == RoleSyncPacket.Role.SURVIVOR && e.state() == SurvivorState.ESCAPED) return TabListStyle.COL_GREEN;
        if (e.role() == RoleSyncPacket.Role.SURVIVOR && e.state() == SurvivorState.ELIMINATED) return TabListStyle.COL_RED;
        return TabListStyle.COL_WHITE;
    }

    /**
     * archetypeId → людська назва маньяка. {@code ManiacRegistry} —
     * спільний реєстр (той самий клас-файл на клієнті й сервері,
     * заповнюється однаковим static-блоком), тому читати
     * {@code displayName()} напряму на клієнті безпечно. Порожній id
     * (маньяк іще не обраний, лобі) чи невідомий id (реєстр порожній —
     * жодного архетипу ще не додано в проєкт) не повинні валити рендер
     * усього табу через один рядок.
     */
    private String maniacDisplayName(String archetypeId) {
        if (archetypeId == null || archetypeId.isEmpty()) return "";
        try {
            ManiacArchetype archetype = ManiacRegistry.get(archetypeId);
            return archetype.displayName();
        } catch (IllegalArgumentException unknownArchetype) {
            return archetypeId;
        }
    }

    // ── Скін гравця ──────────────────────────────────────────────────────

    /**
     * Ванільний {@link PlayerInfo} з мережевого з'єднання — той самий
     * шлях, яким ванільний tab-список бере голови гравців. {@code null},
     * якщо гравця ще нема в трекері з'єднання (щойно приєднався, скін
     * ще не завантажено) — {@link TabListStyle#drawHead} тихо пропускає
     * {@code null}, тому рядок просто лишається без голови на цьому кадрі.
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
