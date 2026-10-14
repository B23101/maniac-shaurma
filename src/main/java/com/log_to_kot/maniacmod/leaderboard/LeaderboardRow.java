package com.log_to_kot.maniacmod.leaderboard;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Один рядок таблиці — рівно те, що потрібно екрану, щоб намалювати гравця: хто, де, скільки,
 * який рівень і яким скіном малювати 3D-бюст. Той самий запис іде по мережі ({@link #write}/{@link #read}).
 *
 * @param rank     місце в цій таблиці, від 1
 * @param value    значення категорії (формат — {@link LeaderboardCategory#format()})
 * @param level    рівень гравця (спільний прогрес)
 * @param favManiac id улюбленого маніяка (найбільше XP) або порожньо
 * @param crowns   у скількох таблицях гравець зараз №1
 * @param skin     значення властивості {@code textures}; порожньо — скін за UUID
 * @param lastSeenMs останній вхід (мс, epoch); 0 — невідомо
 */
public record LeaderboardRow(int rank, UUID id, String name, long value, int level, int matches, int wins,
                             String favManiac, int crowns, boolean online, long lastSeenMs, String skin) {

    /** Скін довший за це на мережу не йде: гравець малюватиметься дефолтним скіном. */
    public static final int MAX_SKIN = 4096;

    public LeaderboardRow {
        name = name == null ? "" : name;
        favManiac = favManiac == null ? "" : favManiac;
        skin = skin == null || skin.length() > MAX_SKIN ? "" : skin;
    }

    public void write(FriendlyByteBuf b) {
        b.writeVarInt(rank);
        b.writeUUID(id);
        b.writeUtf(name, 32);
        b.writeVarLong(value);
        b.writeVarInt(level);
        b.writeVarInt(matches);
        b.writeVarInt(wins);
        b.writeUtf(favManiac, 64);
        b.writeVarInt(crowns);
        b.writeBoolean(online);
        b.writeVarLong(lastSeenMs);
        b.writeUtf(skin, MAX_SKIN);
    }

    public static LeaderboardRow read(FriendlyByteBuf b) {
        return new LeaderboardRow(b.readVarInt(), b.readUUID(), b.readUtf(32), b.readVarLong(),
            b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf(64), b.readVarInt(),
            b.readBoolean(), b.readVarLong(), b.readUtf(MAX_SKIN));
    }
}
