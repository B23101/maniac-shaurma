package com.log_to_kot.maniacmod.net.s2c.actionprogress;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: прогрес підняття непритомного. Йде і рятівнику, і
 * самому непритомному (прапор {@code asVictim} каже, кому саме).
 *
 * ── Без пакета «сховати» ─────────────────────────────────────────────
 * Сервер шле цей пакет ЩОТІКА, поки хтось реально тримає підняття.
 * Щойно тримати перестають — пакети просто припиняються, а клієнт
 * гасить шкалу сам, якщо новий не прийшов за ~250 мс. Так шкала не може
 * «застрягнути» на екрані, якщо пакет-сховання загубився (вихід
 * рятівника, смерть, кінець матчу), і окремого повідомлення про
 * скасування не існує взагалі.
 *
 * Сам прогрес між сесіями на сервері зберігається (рятівник, що
 * відійшов і повернувся, продовжує з того ж місця) — тут лише те, що
 * показати ПРОСТО ЗАРАЗ.
 *
 * @param progressTicks скільки вже набрано
 * @param requiredTicks скільки треба всього
 * @param asVictim      true — це пакет для того, кого піднімають
 */
public record RescueProgressPacket(int progressTicks, int requiredTicks, boolean asVictim)
        implements S2CPacket {

    public RescueProgressPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(progressTicks);
        buf.writeVarInt(requiredTicks);
        buf.writeBoolean(asVictim);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler
            .onRescueProgress(progressTicks, requiredTicks, asVictim);
    }
}
