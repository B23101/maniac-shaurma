package com.log_to_kot.maniacmod.net.s2c.evac;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: ПОВНИЙ стан евакуації для екранного HUD ({@code EvacHudOverlay}).
 *
 * <p>Категорія — matchstate: це стан, а не подія, тож його видно й тому, хто зайшов посеред
 * матчу, і глядачеві. Клієнт нічого не вираховує сам — усі числа з сервера. Шлеться на зміну
 * кроку, на втечу і раз на секунду (щоб таймер відльоту йшов рівно).</p>
 *
 * @param step         крок {@link Step}
 * @param escaped      скільки виживих уже втекло
 * @param total        скільки виживих було в матчі (втекли + вибули + ще в грі)
 * @param secondsLeft  скільки секунд до відльоту (лише {@link Step#CLIMB}), інакше -1
 * @param secondsTotal повний час висіння — для шкали (лише {@link Step#CLIMB}), інакше 0
 */
public record EvacStatusPacket(int step, int escaped, int total, int secondsLeft, int secondsTotal)
    implements S2CPacket {

    /** Грубі кроки для гравця (дрібні стадії {@code EvacStage} назовні не видно). */
    public static final class Step {
        public static final int IDLE = 0;      // ще нічого не викликано
        public static final int CALLED = 1;    // викликано — шукай ракету, запускай на галявині
        public static final int INBOUND = 2;   // ракету запущено, вертоліт летить / спускається
        public static final int ROPE_DROP = 3; // мотузка розмотується
        public static final int CLIMB = 4;     // мотузка готова — лізь
        public static final int DEPARTING = 5; // вертоліт відлітає
        private Step() {}
    }

    public static final EvacStatusPacket IDLE = new EvacStatusPacket(Step.IDLE, 0, 0, -1, 0);

    public EvacStatusPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt() - 1, buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(step);
        buf.writeVarInt(escaped);
        buf.writeVarInt(total);
        buf.writeVarInt(Math.max(secondsLeft, -1) + 1);   // varint не любить від'ємних: зсув на 1
        buf.writeVarInt(secondsTotal);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onEvacStatus(this);
    }
}
