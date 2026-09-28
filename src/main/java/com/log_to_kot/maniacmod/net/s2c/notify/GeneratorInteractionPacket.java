package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Сервер → УСІМ клієнтам матчу: хто зараз ремонтує генератор і хто
 * заливає в нього бензин (ТЗ 13).
 *
 * ── Навіщо пакет, якщо власний клієнт і сам знає ─────────────────────
 * Утримання Shift+ПКМ — суто локальний ввід: клієнт ремонтника знає про
 * нього, а клієнт будь-якого іншого гравця — ні. Поза рук (ремонт /
 * каністра) — це playerlib-анімація на ТІЛІ гравця, і запустити її
 * мусить КОЖЕН клієнт, який цього гравця бачить (той самий принцип, що
 * {@code DownedSurvivorsPacket} для лежачої пози: PAL застосовує стек
 * кожного {@code AbstractClientPlayer}, але наповнити його може лише
 * клієнт, який про позу знає).
 *
 * ── Чому лише на ЗМІНІ стану ─────────────────────────────────────────
 * Вид роботи надходить рівно на переходах «почав ремонт», «перейшов до
 * заливу», «закінчив» — серверна сесія сама знає, коли це сталось
 * ({@code GeneratorModule#markWorking}). Щотікового потоку немає: поза
 * не може змінюватись щотіка, а трафік був би постійним.
 *
 * ── Чому НЕ дельта ───────────────────────────────────────────────────
 * Пакет несе повний стан ОДНОГО гравця (він або нічого, або ремонтує,
 * або заливає) — третій стан виключає перші два, тож пакет завжди
 * самодостатній. Втратити «половину» стану тут неможливо: загублений
 * пакет виправить наступний, а {@code ACTION_NONE} гарантовано йде при
 * кожному закритті сесії.
 *
 * @param playerId гравець, чий вид роботи змінився
 * @param action   {@link #ACTION_NONE}, {@link #ACTION_REPAIR} або
 *                 {@link #ACTION_REFUEL}; інші значення клієнт трактує
 *                 як {@code ACTION_NONE} (не падати через чужий
 *                 модифікований сервер)
 */
public record GeneratorInteractionPacket(UUID playerId, int action) implements S2CPacket {

    /** Не працює з генератором (робота скінчилась або ще не починалась). */
    public static final int ACTION_NONE = 0;

    /** Ремонт (стадія REPAIR). */
    public static final int ACTION_REPAIR = 1;

    /** Залив пального з каністри (стадія FUEL). */
    public static final int ACTION_REFUEL = 2;

    public GeneratorInteractionPacket(FriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(playerId);
        buf.writeVarInt(action);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onGeneratorInteraction(playerId, action);
    }
}
