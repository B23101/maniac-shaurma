package com.log_to_kot.maniacmod.maniacs;

import com.log_to_kot.maniacmod.abilities.Ability;
import com.log_to_kot.maniacmod.traps.BearTrapArchetype;
import com.log_to_kot.maniacmod.traps.TrapArchetype;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Тестовий маньяк — ТИМЧАСОВА заглушка, поки немає справжнього.
 *
 * ── Навіщо ───────────────────────────────────────────────────────────
 * Реєстр маньяків порожній, а без маньяка неможливо перевірити ні
 * пастки, ні швидкість. Цей клас — «просто модель гравця»: жодної
 * власної сутності, моделі чи здібностей; гравець лишається у своєму
 * скіні. Габарити збігаються з ванільним гравцем (0.6 × 1.8, очі 1.62),
 * тож нічого в поведінці не змінюється.
 *
 * ── Як замінити справжнім ────────────────────────────────────────────
 * Створити свій {@code XxxArchetype}, зареєструвати його в
 * {@link ManiacRegistry} і прибрати рядок {@code register(new TestManiacArchetype())}
 * звідти. Нічого іншого міняти не треба: пастки, швидкість і меню
 * читають АРХЕТИП, а не цей клас.
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
        // Габарити ванільного гравця: 0.6 x 1.8, очі 1.62, без зміни FOV/масштабу.
        super(ID, "Тестовий маньяк", 0.6f, 1.8f, 1.62f, 1.0f, 1.0f);
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
