package com.log_to_kot.maniacmod.maniacs;

import com.log_to_kot.maniacmod.abilities.Ability;
import com.log_to_kot.maniacmod.traps.BearTrapArchetype;
import com.log_to_kot.maniacmod.traps.TrapArchetype;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Тестовий маньяк — шаблон, на якому відпрацьовано всю технологію
 * маньяка (висота хітбокса, очі, гео-модель, анімації).
 *
 * ── Що він доводить ──────────────────────────────────────────────────
 * Гравець-маньяк стає ЗДОРОВИМ триблочним персонажем із власною
 * гео-моделлю замість скіна гравця: хітбокс 1.2 × 3.0, очі 2.7, вісім
 * анімацій (idle/walk/run/jump/sneak/sneak_walk/attack/interact), які
 * код перемикає сам, без участі гравця.
 *
 * Асе́ти — за конвенцією від {@link #ID} (див. {@code ManiacVisuals}):
 * {@code geo/entity/test_maniac.geo.json},
 * {@code animations/entity/test_maniac.animation.json},
 * {@code textures/entity/test_maniac.png}.
 *
 * Налаштування — у {@code config/maniacmod/maniac_stats/test_maniac.yml}:
 * числа в конструкторі нижче — це лише ДЕФОЛТИ для цього файла, який
 * створюється сам при першому запуску. Тобто щоб зробити цього маньяка
 * вдвічі вищим або набагато швидшим, компіляція не потрібна.
 *
 * ── Як додати справжнього маньяка ────────────────────────────────────
 *   1. {@code MyManiacArchetype extends ManiacArchetype}, id {@code "my_maniac"}:
 *      габарити й око в конструктор, здібності/пастки у свої методи,
 *      за потреби — {@code declaredAttackDamage()} тощо;
 *   2. асе́ти під тим самим id: {@code my_maniac.geo.json} /
 *      {@code my_maniac.animation.json} / {@code my_maniac.png}
 *      з такими самими іменами кісток і анімацій;
 *   3. {@code register(new MyManiacArchetype());} у {@link ManiacRegistry}.
 * Більше НІДЕ нічого правити не треба: хітбокс, очі, модель, анімації,
 * меню вибору й команди читають архетип. І окремо від усього цього
 * реєстрація створює {@code config/maniacmod/maniac_stats/my_maniac.yml}
 * — габарити, шкода, дальність, перезарядка й множник швидкості цього
 * маньяка; решту маньяків він не чіпає (див. {@code ManiacStats}).
 *
 * ── Що навмисно порожнє ──────────────────────────────────────────────
 *   • {@link #weaponItem()} — {@code Items.AIR}: жодного коду не
 *     споживає зброю (удар рахує {@code ManiacCombatModule} за
 *     дальністю), а свого предмета тестовий маньяк не має;
 *   • {@link #abilities()} — порожньо: здібності ще не перенесено.
 */
public final class TestManiacArchetype extends ManiacArchetype {

    public static final String ID = "test_maniac";

    public TestManiacArchetype() {
        // Триблочний маньяк: ширина 1.2, висота 3.0, очі 2.7 (90% висоти —
        // як у ванільного гравця 1.62/1.8), без зміни FOV/масштабу.
        super(ID, "Тестовий маньяк", 1.2f, DEFAULT_HITBOX_HEIGHT, 2.7f, 1.0f, 1.0f);
    }

    @Override
    public Item weaponItem() {
        return Items.AIR;
    }

    @Override
    public List<Ability> abilities() {
        return List.of();
    }

    /** Поки в реєстрі одна пастка — капкан. Решта додасться разом із реалізацією. */
    @Override
    public List<TrapArchetype> traps() {
        return List.of(new BearTrapArchetype());
    }
}
