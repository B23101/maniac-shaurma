package com.log_to_kot.maniacmod.client.glow;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;

/**
 * Клієнтський реєстр сутностей, які мають світитися КОНТУРОМ лише на
 * цьому клієнті.
 *
 * ── Технологія (чому так) ─────────────────────────────────────────────
 * Ванільний {@code Entity.setGlowingTag(true)} — це біт у метаданих
 * сутності, який сервер розсилає ВСІМ: будь-який гравець (включно з
 * маньяком) побачив би підсвітку. Окремого «світіння для одного
 * гравця» у ванілі немає.
 *
 * Саме тому тут світіння робиться НЕ через метадані, а суто клієнтськи:
 * контур малює {@code LevelRenderer} у окремому проході, і єдине, що
 * вирішує «малювати чи ні» — результат {@code Entity.isCurrentlyGlowing()}.
 * Міксин {@code EntityGlowMixin} підміняє цей метод на клієнті: якщо id
 * сутності є в цьому реєстрі — повертається {@code true}. Оскільки метод
 * викликається лише у рендері, реєстр фізично не впливає ні на сервер,
 * ні на інших гравців: кожен клієнт має власну копію цього списку й
 * наповнює її лише тим, що йому прислав сервер.
 *
 * Це той самий підхід, яким користуються клієнтські моди-аутлайнери
 * («entity xray / ESP»): не чіпати метадані, а лише керувати
 * {@code isCurrentlyGlowing()} локально.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientEntityGlow {

    /** id сутності → ARGB-колір контуру (без альфи — вона не потрібна рендеру). */
    private static final Map<Integer, Integer> COLORS = new HashMap<>();

    private ClientEntityGlow() {}

    /**
     * Повністю замінює склад підсвічених сутностей. Викликається раз на
     * клієнтський тік (див. {@link GeneratorHighlightGlow}): заміна
     * цілком, а не інкрементально, бо джерело правди — список
     * генераторів, який міг змінитися (генератор вибухнув, гравець
     * натиснув 5 повторно, матч скінчився).
     */
    static void replace(Map<Integer, Integer> next) {
        COLORS.clear();
        COLORS.putAll(next);
    }

    /** Прибирає всю підсвітку — кінець дії підсвітки, вихід з матчу. */
    public static void clear() {
        COLORS.clear();
    }

    /** Чи має ця сутність світитися на цьому клієнті. Викликає міксин. */
    public static boolean isGlowing(int entityId) {
        return COLORS.containsKey(entityId);
    }

    /** Колір контуру для цієї сутності. {@code 0xFFFFFF}, якщо її немає. */
    public static int color(int entityId) {
        return COLORS.getOrDefault(entityId, 0xFFFFFF);
    }
}
