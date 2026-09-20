package com.log_to_kot.maniacmod.net.s2c.actionprogress;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: прогрес генератора, який гравець зараз ремонтує.
 *
 * ── Категорія: actionprogress ────────────────────────────────────────
 * Прогрес конкретної дії, що триває ЗАРАЗ і стосується лише того, хто
 * її виконує (ремонт, підсвітка, кулдаун). На відміну від
 * {@code vitals} — це не постійний стан гравця, а прогрес однієї
 * взаємодії, яка почалась і закінчиться. Порожній {@code hidden()} —
 * штатний спосіб сказати "дії більше немає", а не окремий пакет.
 *
 * v3-еквівалент: однойменний пакет, але без стадії заливу бензину —
 * тепер одним пакетом передаються обидві стадії дизайну.
 *
 * @param visible      false = сховати бар (гравець відпустив ПКМ)
 * @param stage        0 = ремонт, 1 = бензин
 * @param stagePercent прогрес ПОТОЧНОЇ стадії, 0–100, нормалізований
 *                     сервером: для REPAIR — відсоток ремонту, для FUEL —
 *                     частка від {@code fuelRequiredPercent}. Бар клієнта
 *                     малює саме його й нічого не ділить сам.
 * @param minigamesDone скільки СТАДІЙ уже пройдено (0 під час ремонту,
 *                      1 під час заливу) — поле зберегло стару назву заради
 *                      сумісності формату пакета
 * @param minigamesTotal скільки стадій усього (2); 0 ховає підпис «етапи»
 * @param fuelPercent  залито бензину в сирих відсотках (0…fuelRequiredPercent) —
 *                     лише для підпису, не для довжини бара
 */
public record GeneratorProgressPacket(boolean visible, int stage, int stagePercent,
                                       int minigamesDone, int minigamesTotal,
                                       int fuelPercent) implements S2CPacket {

    /** Сховати бар. */
    public static GeneratorProgressPacket hidden() {
        return new GeneratorProgressPacket(false, 0, 0, 0, 0, 0);
    }

    public GeneratorProgressPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
             buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(visible);
        buf.writeVarInt(stage);
        buf.writeVarInt(stagePercent);
        buf.writeVarInt(minigamesDone);
        buf.writeVarInt(minigamesTotal);
        buf.writeVarInt(fuelPercent);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onGeneratorProgress(this);
    }
}
