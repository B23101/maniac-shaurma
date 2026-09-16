package com.log_to_kot.maniacmod.net.s2c.vitals;

import com.log_to_kot.maniacmod.net.S2CPacket;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: показники ВЛАСНОГО виживого для HUD.
 *
 * ── Категорія: vitals ────────────────────────────────────────────────
 * Дані про СЕБЕ, що змінюються часто (щотік/щохвилини) — на відміну
 * від {@code identity} (роль — раз на матч) чи {@code matchstate}
 * (фаза — спільна для всіх). Якщо колись знадобиться бачити хп/стан
 * ІНШОГО гравця (наприклад для майбутнього roster/tab) — це НЕ
 * розширення цього пакета полем "чий це хп": tab повинен запитувати
 * дані через свій власний roster-пакет з посиланнями (UUID → стан),
 * а не дублювати числа, які вже летять сюди власнику. Два пакети з
 * однаковими числами — це два джерела правди, яких і стосується
 * головне правило AI_CODE_GUIDE.md.
 *
 * ОДИН пакет на всі показники замість трьох окремих. Хп, стаміна й
 * стан змінюються разом і малюються разом — надсилати їх різними
 * пакетами означало б три рази на тік слати по кілька байтів і
 * ризикувати, що HUD покаже хп з одного тіку, а стан з іншого.
 *
 * Надсилається лише коли значення реально змінились, не щотік.
 *
 * @param hp            поточне здоров'я
 * @param maxHp         максимум (100 у звичайного виживого)
 * @param stamina       0.0–1.0
 * @param state         поточний стан (HEALTHY / BROKEN_LEG / ...)
 * @param heartbeat     0.0–1.0, наскільки близько маньяк (0 = не чути)
 */
public record SurvivorVitalsPacket(int hp, int maxHp, float stamina,
                                    SurvivorState state, float heartbeat) implements S2CPacket {

    public SurvivorVitalsPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readFloat(),
             buf.readEnum(SurvivorState.class), buf.readFloat());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(hp);
        buf.writeVarInt(maxHp);
        buf.writeFloat(stamina);
        buf.writeEnum(state);
        buf.writeFloat(heartbeat);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler
            .onVitals(hp, maxHp, stamina, state, heartbeat);
    }
}
