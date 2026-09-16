package com.log_to_kot.maniacmod.net.s2c.actionprogress;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: початок перезарядки дії маньяка.
 *
 * ── Категорія: actionprogress ────────────────────────────────────────
 * Той самий патерн, що {@link GeneratorProgressPacket}: сервер шле
 * тривалість один раз, клієнт відраховує сам. Один пакет на всі
 * перезарядки — удару, здібностей, пасток — щоб додавання нової
 * здібності не означало новий тип пакета.
 *
 * Перезарядка удару окремо важлива: саме за нею міксин глушить
 * наступне натискання ЛКМ, щоб не було замаху без удару.
 *
 * @param actionId   що саме перезаряджається
 * @param totalTicks повна тривалість; 0 = готово
 */
public record AbilityCooldownPacket(String actionId, int totalTicks) implements S2CPacket {

    /** Удар маньяка. Зарезервований id — не використовувати для здібності. */
    public static final String ATTACK_ID = "attack";

    /** Здібність за номером клавіші 1–3. */
    public static String abilityId(int slot) {
        return "ability:" + slot;
    }

    /** Пастка за номером слота Z/X/C. */
    public static String trapId(int slot) {
        return "trap:" + slot;
    }

    /** Перезарядка завершена. */
    public static AbilityCooldownPacket ready(String actionId) {
        return new AbilityCooldownPacket(actionId, 0);
    }

    public AbilityCooldownPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(), buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(actionId);
        buf.writeVarInt(totalTicks);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler
            .onAbilityCooldown(actionId, totalTicks);
    }
}
