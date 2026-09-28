package com.log_to_kot.maniacmod.client.glow;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * ── Чому ШАРИ, а не один спільний map ────────────────────────────────
 * Раніше реєстр був одним {@code Map}, і кожен «драйвер» (напр.
 * підсвітка генераторів клавішею 5) щотіка кликав {@code replace(...)} —
 * тобто повністю ПЕРЕЗАПИСУВАВ склад підсвічених. Щойно з'явився другий
 * незалежний джерело (жертва в пастці, яку має бачити вся команда),
 * один драйвер почав би стирати підсвітку іншого — «то видно, то
 * зникає» залежно від порядку тіків. Тому кожен джерело тепер пише у
 * СВІЙ іменований шар ({@link #setLayer}), а {@link #isGlowing}
 * підсумовує всі шари. Драйвери більше не знають один про одного.
 *
 * Кольори кількох шарів для однієї сутності не змішуються: перемагає
 * шар, доданий раніше (перший-ліпший у порядку вставки). Практично
 * одна сутність майже ніколи не світиться двома джерелами одночасно.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientEntityGlow {

    /** Ім'я шару підсвітки генераторів (див. GeneratorHighlightGlow). */
    public static final String LAYER_GENERATOR = "generator";

    /** Ім'я шару підсвітки жертв пасток (див. TrapVictimGlow). */
    public static final String LAYER_TRAP_VICTIM = "trap_victim";

    /**
     * шар → (id сутності → ARGB-колір без альфи). {@code LinkedHashMap},
     * щоб порядок шарів був детермінований (див. докстрінг класу про
     * пріоритет кольору).
     */
    private static final Map<String, Map<Integer, Integer>> LAYERS = new LinkedHashMap<>();

    private ClientEntityGlow() {}

    /**
     * Повністю замінює вміст ОДНОГО шару. Заміна цілком, а не
     * інкрементально, бо джерело правди — список, який міг змінитися
     * (генератор вибухнув, гравець натиснув 5 повторно, матч скінчився).
     * Викликається раз на клієнтський тік відповідним драйвером.
     */
    static void setLayer(String layer, Map<Integer, Integer> next) {
        if (next.isEmpty()) {
            LAYERS.remove(layer);
        } else {
            LAYERS.put(layer, new HashMap<>(next));
        }
    }

    /** Прибирає лише один шар (кінець конкретного джерела підсвітки). */
    public static void clearLayer(String layer) {
        LAYERS.remove(layer);
    }

    /** Прибирає всю підсвітку — вихід з матчу, дисконект. */
    public static void clear() {
        LAYERS.clear();
    }

    /** Чи має ця сутність світитися на цьому клієнті. Викликає міксин. */
    public static boolean isGlowing(int entityId) {
        for (Map<Integer, Integer> layer : LAYERS.values()) {
            if (layer.containsKey(entityId)) return true;
        }
        return false;
    }

    /** Колір контуру для цієї сутності. {@code 0xFFFFFF}, якщо її немає. */
    public static int color(int entityId) {
        for (Map<Integer, Integer> layer : LAYERS.values()) {
            Integer color = layer.get(entityId);
            if (color != null) return color;
        }
        return 0xFFFFFF;
    }
}
