package com.log_to_kot.maniacmod.traps;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Базовий контракт для будь-якого типу пастки маньяка.
 *
 * ── Що спільне, а що різне ───────────────────────────────────────────
 * Усі пастки працюють за ОДНІЄЮ логікою життєвого циклу:
 *   вибір у меню → кнопка 5/6/7 → режим розміщення → ПКМ підтвердити →
 *   пастка стоїть у світі → хтось потрапив → ефект → звільнення.
 * Цей цикл живе в {@link TrapModule} ОДИН раз. Пастка ж відрізняється
 * лише трьома речами, і саме їх і описує цей клас:
 *   1. ДЕ можна поставити ({@link #placementShape()} + {@link #validatePlacement});
 *   2. ЩО з'являється у світі ({@link #spawn});
 *   3. ЩО відбувається з жертвою ({@link #applyEffect}) і як її звільнити.
 *
 * Це саме та межа, про яку просив дизайн: «по логіці однакові, різні
 * функції». Нова пастка = один підклас + один рядок у {@link TrapRegistry};
 * {@link TrapModule}, мережа й HUD не змінюються.
 *
 * ── Форма розміщення ─────────────────────────────────────────────────
 * Капкан — один блок. Розтяжка (2 точки на одному рівні, ≤ 4 блоків,
 * без суцільної стіни між ними) і мотузка (потрібна стеля 2–5 блоків) —
 * інші форми. Контракт їх уже виражає ({@link PlacementShape}), але
 * реалізовано поки лише {@link PlacementShape#SINGLE_BLOCK}: решту
 * додаватимуть разом із самими пастками, а не заздалегідь.
 *
 * ЗМІНА ТУТ → впливає одразу на всі пастки.
 */
public abstract class TrapArchetype {

    /** Скільки точок маньяк вибирає, щоб поставити пастку. */
    public enum PlacementShape {
        /** Один блок-підлога (капкан, міна). */
        SINGLE_BLOCK,
        // TWO_POINTS,   // розтяжка: 2 точки на одному рівні, ≤ 4 блоків, без стіни між
        // CEILING_LINK  // мотузка: потрібна стеля на висоті 2–5 блоків
    }

    /** Чому місце не підходить. Для червоного квадрата й повідомлення маньяку. */
    public enum PlacementResult {
        OK,
        /** Не повноцінний блок (слаб, килим, сходи, повітря...). */
        NOT_FULL_BLOCK,
        /** Над блоком немає вільного місця для самої пастки. */
        NO_ROOM_ABOVE,
        /** Задалеко від очей маньяка. */
        TOO_FAR,
        /** Надто близько до гравця (див. {@link TrapPlacementRules}). */
        TOO_CLOSE_TO_PLAYER,
        /** На цьому блоці вже стоїть пастка. */
        OCCUPIED,
        /** Слот на перезарядці або пастку не обрано в цьому матчі. */
        UNAVAILABLE;

        public boolean ok() { return this == OK; }
    }

    protected final String id;               // "bear_trap", "electric_wire", ...
    protected final String displayName;

    protected TrapArchetype(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    // ── Контракт, який кожна пастка МУСИТЬ реалізувати ──────────────────────

    /** Скільки точок треба вибрати. Клієнт за цим вирішує, який режим розміщення запускати. */
    public abstract PlacementShape placementShape();

    /**
     * Перевірка МІСЦЯ, яка стосується САМЕ цього типу пастки (форма
     * підлоги, висота стелі...). Спільні правила «не ближче N блоків до
     * гравця» сюди НЕ входять — вони в {@link TrapPlacementRules}.
     *
     * ── Чому {@link BlockGetter}, а не ServerLevel ───────────────────
     * Ця функція викликається ДВІЧІ: на клієнті — щокадру, щоб
     * показати зелений/червоний квадрат, і на сервері — при
     * підтвердженні. Обидва мають бачити ОДНЕ Й ТЕ САМЕ рішення, інакше
     * квадрат світитиметься зеленим там, де сервер відмовить. Тому
     * функція приймає лише те, що є в обох світах, — блоки навколо.
     * Нічого про гравців тут знати не можна: клієнт їх позицій не має
     * (і не повинен — маньяк не має бачити виживих крізь стіни через
     * колір квадрата).
     *
     * @param floor блок, НА який ставиться пастка (сама пастка стоїть над ним)
     */
    public abstract PlacementResult validatePlacement(BlockGetter level, BlockPos floor);

    /**
     * Створює тіло пастки у світі (сутність) і додає його на рівень.
     *
     * @return створена сутність, або {@code null}, якщо додати не вдалось
     */
    public abstract Entity spawn(ServerLevel level, BlockPos floor, ServerPlayer placer);

    /** Ефект на гравця, що потрапив у пастку (знерухомлення, урон, зв'язування тощо). */
    public abstract void applyEffect(ServerPlayer victim, BlockPos trapPos);

    /** Чи спрацьовує пастка для цього гравця прямо зараз (виживий, ще не в пастці тощо). */
    public abstract boolean shouldTrigger(ServerPlayer candidate);

    // ── Спільна логіка (ОДНА реалізація для всіх пасток) ────────────────────

    /**
     * Кулдаун розміщення. За замовчуванням — спільне значення з
     * конфігу; пастка, якій потрібен свій, перевизначає цей метод.
     *
     * Окремого поля немає навмисно: інакше число жило б і в конфігу,
     * і в конструкторі кожної пастки, і рано чи пізно розійшлося б.
     */
    public int placementCooldownTicks() {
        return ManiacConfigs.get(ConfigSchema.TRAP_PLACE_COOLDOWN_TICKS);
    }

    public final String id()          { return id; }
    public final String displayName() { return displayName; }
}
