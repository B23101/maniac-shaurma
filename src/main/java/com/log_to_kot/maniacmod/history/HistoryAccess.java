package com.log_to_kot.maniacmod.history;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Хто що бачить в історії. Єдине місце правил — сервер перевіряє їх заново на кожен запит, не довіряючи
 * клієнту (екран лише ховає кнопки).
 *
 * <ul>
 *   <li><b>Усі матчі</b> — лише адмін ({@code hasPermissions(2)}, той самий рівень, що в адмін-відгуків).</li>
 *   <li><b>Особиста історія гравця</b> — власник профілю; адмін; а також той, хто дивиться чужий профіль:
 *       за тим самим правилом, що й сам профіль ({@code ProfileRequestPacket}) — лише в лобі й лише для
 *       гравця онлайн. Під час матчу чужа історія не стає каналом розвідки.</li>
 *   <li><b>Один матч</b> — адмін; його учасник; або той, хто відкрив його з історії гравця, чию історію
 *       йому дозволено бачити (матч мусить справді містити цього гравця).</li>
 * </ul>
 */
public final class HistoryAccess {

    private HistoryAccess() {}

    public static boolean isAdmin(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    /**
     * @param target       чия історія; {@code null} — усі матчі
     * @param lobby        чи матч зараз у фазі LOBBY
     * @param targetOnline чи {@code target} зараз онлайн
     */
    public static boolean canList(ServerPlayer requester, UUID target, boolean lobby, boolean targetOnline) {
        if (isAdmin(requester)) return true;
        if (target == null) return false;
        if (target.equals(requester.getUUID())) return true;
        return lobby && targetOnline;
    }

    /**
     * @param contextTarget чий профіль/історію відкрито, звідки клікнули матч; {@code null} — відкрито не з профілю
     */
    public static boolean canOpen(ServerPlayer requester, MatchRecord record, UUID contextTarget,
                                  boolean lobby, boolean contextOnline) {
        if (isAdmin(requester)) return true;
        if (record.involves(requester.getUUID())) return true;
        return contextTarget != null && record.involves(contextTarget)
            && canList(requester, contextTarget, lobby, contextOnline);
    }
}
