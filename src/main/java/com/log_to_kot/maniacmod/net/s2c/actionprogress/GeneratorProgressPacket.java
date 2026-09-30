package com.log_to_kot.maniacmod.net.s2c.actionprogress;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * @param fuelRequired скільки відсотків бензину треба залити всього
 *                     ({@code fuelRequiredPercent}); клієнт ділить бар
 *                     на секції по 100% (200% — дві, 300% — три)
 * @param workers      хто зараз працює над ЦИМ генератором (ремонт або
 *                     залив), разом із самим отримувачем — клієнт малює
 *                     їхні голови під шкалою
 */
public record GeneratorProgressPacket(boolean visible, int stage, int stagePercent,
                                       int minigamesDone, int minigamesTotal,
                                       int fuelPercent, int fuelRequired,
                                       List<UUID> workers) implements S2CPacket {

    /** Сховати бар. */
    public static GeneratorProgressPacket hidden() {
        return new GeneratorProgressPacket(false, 0, 0, 0, 0, 0, 0, List.of());
    }

    public GeneratorProgressPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
             buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
             readWorkers(buf));
    }

    private static List<UUID> readWorkers(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), 16); // захист від чужого модифікованого сервера
        List<UUID> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(buf.readUUID());
        return out;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(visible);
        buf.writeVarInt(stage);
        buf.writeVarInt(stagePercent);
        buf.writeVarInt(minigamesDone);
        buf.writeVarInt(minigamesTotal);
        buf.writeVarInt(fuelPercent);
        buf.writeVarInt(fuelRequired);
        int n = Math.min(workers.size(), 16);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) buf.writeUUID(workers.get(i));
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onGeneratorProgress(this);
    }
}
