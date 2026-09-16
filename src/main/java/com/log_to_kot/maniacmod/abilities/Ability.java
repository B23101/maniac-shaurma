package com.log_to_kot.maniacmod.abilities;

import net.minecraft.server.level.ServerPlayer;

/**
 * Одна активна здібність маньяка — незалежний "плагін".
 *
 * Ключова ідея: здібність НЕ прив'язана до конкретного маньяка.
 * Один і той самий клас (напр. DashAbility) можна додати в
 * abilities() і Chucky, і майбутнього іншого швидкого маньяка —
 * без копіювання коду ривка. Кожен маньяк лише вирішує, ЯКІ
 * здібності в нього є (список у ManiacArchetype.abilities()),
 * а не ЯК вони працюють.
 *
 * Приклад використання (коли переноситься реальна логіка):
 *   public List<Ability> abilities() {
 *       return List.of(new DashAbility(20, 200)); // дальність, кулдаун
 *   }
 */
public interface Ability {

    /** Унікальний ключ здібності, напр. "dash", "invisibility" — для HUD/мережі. */
    String id();

    /** Кулдаун між використаннями, в тіках. */
    int cooldownTicks();

    /**
     * Виконати здібність для гравця-маньяка. Повертає true, якщо
     * здібність спрацювала (і кулдаун треба почати відлік);
     * false — якщо умови не виконані (напр. немає цілі в радіусі).
     */
    boolean activate(ServerPlayer maniacPlayer);
}
