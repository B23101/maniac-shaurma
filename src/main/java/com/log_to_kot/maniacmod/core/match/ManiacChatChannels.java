package com.log_to_kot.maniacmod.core.match;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import dev.shaurmalib.common.chat.ChatChannel;
import dev.shaurmalib.common.chat.ChatChannelProvider;
import dev.shaurmalib.common.chat.ChatChannelRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Рольові чат-канали маніяка: лобі, виживші, маньяки, глядачі.
 *
 * <p>Це єдина точка, де мод каже бібліотеці, ХТО куди пише і КОГО досягне
 * повідомлення. Сама бібліотека про ці канали не знає нічого — вони просто
 * зареєстровані як звичайні {@link ChatChannel} зі своїми id і кольорами, а
 * правила доступу лежать тут, у продуктовому коді режиму.</p>
 *
 * <p>Правило просте і навмисно плоске:</p>
 * <ul>
 *   <li><b>технічні фази</b> (лобі, кіно, розкидання, оголошення ролей,
 *       підсумки, скидання) — усі пишуть у спільний канал {@link #LOBBY};
 *       рольових каналів ще немає, бо ролі або не призначені, або ось-ось
 *       перепризначаться;</li>
 *   <li><b>ігрові фази</b> (HUNT/POWERED/FINALE) — гравець пише лише у свій
 *       канал: маньяк бачить маньяків, виживий — виживих, глядач —
 *       глядачів.</li>
 * </ul>
 *
 * <p>Глобальний канал бібліотека обробляє сама — він не в цьому списку і
 * доступний завжди.</p>
 */
public final class ManiacChatChannels implements ChatChannelProvider {

    public static final String LOBBY = "lobby";
    public static final String SURVIVORS = "survivors";
    public static final String MANIACS = "maniacs";
    public static final String SPECTATORS = "spectators";

    private static final String COLOR_LOBBY = "#B9C4CF";
    private static final String COLOR_SURVIVORS = "#6FD36F";
    private static final String COLOR_MANIACS = "#E0503C";
    private static final String COLOR_SPECTATORS = "#7FA8D8";

    /**
     * Реєструє канали в бібліотеці. Викликати один раз на старті сервера —
     * канали статичні й переживуть вихід у головне меню, якщо їх не зняти.
     */
    public static void registerChannels() {
        ChatChannelRegistry.register(new ChatChannel(LOBBY,
            "maniacmod.chat.channel.lobby", 0xFFB9C4CF));
        ChatChannelRegistry.register(new ChatChannel(SURVIVORS,
            "maniacmod.chat.channel.survivors", 0xFF6FD36F));
        ChatChannelRegistry.register(new ChatChannel(MANIACS,
            "maniacmod.chat.channel.maniacs", 0xFFE0503C));
        ChatChannelRegistry.register(new ChatChannel(SPECTATORS,
            "maniacmod.chat.channel.spectators", 0xFF7FA8D8));
    }

    // ── ChatChannelProvider ─────────────────────────────────────────────

    @Override
    public List<String> writableChannels(UUID sender) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return List.of();

        GamePhase phase = match.phases().current();
        if (!phase.isGameplay()) {
            // НЕ List.of(LOBBY): у нерольовій фазі одержувачі LOBBY — це всі
            // онлайн-гравці сервера, тобто буквально те саме, що глобальний
            // канал. Повертаючи окремий канал тут, ми змушували
            // ChatHistoryScreen малювати ДВІ кнопки ("Загальний" і "Лобі")
            // на одну й ту саму аудиторію. Бібліотека документує порожній
            // список саме як контракт "лише глобальний чат" (див.
            // ChatChannelProvider.writableChannels) — це і є коректний стан
            // для лобі, а не окремий канал.
            return List.of();
        }
        return List.of(groupOf(match, sender));
    }

    @Override
    public List<UUID> recipientsOf(String channelId, UUID sender) {
        MatchOrchestrator match = ManiacMod.match();
        MinecraftServer server = match == null ? null : match.server();
        if (server == null) return List.of(sender);

        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        List<UUID> recipients = new ArrayList<>();
        for (ServerPlayer player : online) {
            if (LOBBY.equals(channelId) || groupOf(match, player.getUUID()).equals(channelId)) {
                recipients.add(player.getUUID());
            }
        }
        return recipients;
    }

    @Override
    public String colorHexOf(String channelId, UUID sender) {
        return switch (channelId) {
            case SURVIVORS -> COLOR_SURVIVORS;
            case MANIACS -> COLOR_MANIACS;
            case SPECTATORS -> COLOR_SPECTATORS;
            case LOBBY -> COLOR_LOBBY;
            default -> null;
        };
    }

    // ── Допоміжне ───────────────────────────────────────────────────────

    /** Рольова група гравця зараз: MANIACS / SURVIVORS / SPECTATORS. */
    private static String groupOf(MatchOrchestrator match, UUID playerId) {
        if (match.isManiac(playerId)) return MANIACS;
        if (match.isSurvivor(playerId)) return SURVIVORS;
        return SPECTATORS;
    }
}
