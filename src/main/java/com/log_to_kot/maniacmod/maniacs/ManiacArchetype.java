package com.log_to_kot.maniacmod.maniacs;

import com.log_to_kot.maniacmod.abilities.Ability;
import com.log_to_kot.maniacmod.config.ManiacStats;
import com.log_to_kot.maniacmod.traps.TrapArchetype;
import net.minecraft.world.item.Item;

import java.util.List;

/**
 * Базовий контракт маньяка.
 *
 * ── Дві половини одного персонажа ────────────────────────────────────
 * Клас-архетип задає:
 *   • <b>дані</b> — габарити, зброю, здібності, пастки;
 *   • <b>дефолти балансу</b> — {@code declaredXxx()}: дальність удару,
 *     шкода, перезарядка, множник швидкості.
 * Обидві половини перекриваються конфігом ЦЬОГО маньяка
 * ({@link ManiacStats}): {@code config/maniacmod/maniac_stats/<id>.yml}.
 * Тобто код вирішує, ЯКИЙ персонаж, а yml — наскільки він сильний,
 * швидкий і високий у конкретному світі. Компіляція для балансу не
 * потрібна, а спільного ключа «на всіх» немає: правити одного маньяка
 * не означає чіпати решту.
 *
 * ── Жорсткі межі, які НЕ налаштовуються ──────────────────────────────
 * У маньяка немає інвентаря, тому клавіші 1–4 вільні:
 *   1 / 2 / 3 — здібності (максимум {@link #MAX_ABILITIES})
 *   5 / 6 / 7 — пастки (максимум {@link #MAX_TRAP_SLOTS})
 *   ЛКМ      — удар
 * Ці числа — константи, а не конфіг: збільшити їх нікуди, бо клавіш
 * рівно стільки. Архетип, що віддасть більше, падає одразу при
 * реєстрації, а не мовчки загубить четверту здібність у грі.
 *
 * ── Зовнішність ──────────────────────────────────────────────────────
 * Зовнішність задає {@link #visuals()} — за замовчуванням це
 * {@link ManiacVisuals#of(String)} від {@link #id}, тобто гео-модель,
 * текстура й анімації, названі як сам архетип. Клієнт бере їх сам за
 * id, який прилітає в ростері, — жодного коду на нового маньяка
 * дописувати не треба (див. {@code ManiacVisuals}).
 *
 * Габарити ({@code hitboxWidth/hitboxHeight/eyeHeight}) застосовуються
 * до гравця-маньяка на ОБОХ сторонах: сервер — {@code ManiacBodyEvents},
 * клієнт — {@code ClientManiacBodyEvents}. Сервер надсилає їх у ростері
 * разом з id архетипу, тому виділений сервер із власними числами не
 * розходиться з клієнтами (див. {@code RosterSyncPacket.ManiacBody}).
 *
 * ── Що змінилось для тих, хто вже писав архетип ──────────────────────
 * {@link #attackRangeBlocks()}, {@link #attackCooldownTicks()},
 * {@link #attackDamage()} і {@link #speedMultiplier()} більше НЕ
 * перевизначаються підкласом: тепер це ФІНАЛЬНІ гетери «скільки
 * зараз», а власний характер задають {@code declaredXxx()}. Так одне
 * число має рівно одне джерело правди й не лишається «мертвим
 * override», який конфіг перекрив і зробив непомітним.
 */
public abstract class ManiacArchetype {

    /** Здібності: клавіші 1, 2, 3. */
    public static final int MAX_ABILITIES = 3;

    /**
     * Висота хітбокса за замовчуванням, якщо архетип не задав свою.
     * Маньяк вищий за гравця (1.8) — три блоки.
     */
    public static final float DEFAULT_HITBOX_HEIGHT = 3.0f;

    /** Пастки: клавіші 5, 6, 7. */
    public static final int MAX_TRAP_SLOTS = 3;

    /** У маньяка немає інвентаря — звідси й вільні клавіші 1–4. */
    public static final int HOTBAR_SLOTS = 0;

    /**
     * Дефолти балансу: те саме, що колись лежало в спільному блоці
     * конфігу. Дальність 3.5 — не «кругле число»: ванільний гравець
     * дістає на 3.0 блока, тож маньяк мусить діставати трохи далі, щоб
     * удар не зривався об різницю пінгів, а виживий не танцював на межі
     * досяжності. Це дефолт у файлі КОЖНОГО маньяка — правиться без
     * перекомпіляції.
     */
    public static final double DEFAULT_SPEED_MULTIPLIER = 1.2;
    public static final double DEFAULT_ATTACK_RANGE_BLOCKS = 3.5;
    public static final int    DEFAULT_ATTACK_COOLDOWN_TICKS = 120;
    public static final int    DEFAULT_ATTACK_DAMAGE = 50;

    // Оголошені кодом дані. Префікс «declared» не для краси: поруч є
    // гетери без префікса, і різниця між «що заклав автор архетипу» та
    // «що діє зараз із конфігу» мусить бути видна в самому імені.
    private final String id;
    private final String displayName;
    private final float declaredHitboxWidth;
    private final float declaredHitboxHeight;
    private final float declaredEyeHeight;
    private final float declaredFovMultiplier;
    private final float declaredModelScale;

    protected ManiacArchetype(String id, String displayName,
                              float hitboxWidth, float hitboxHeight, float eyeHeight,
                              float fovMultiplier, float modelScale) {
        this.id = id;
        this.displayName = displayName;
        this.declaredHitboxWidth = hitboxWidth;
        this.declaredHitboxHeight = hitboxHeight;
        this.declaredEyeHeight = eyeHeight;
        this.declaredFovMultiplier = fovMultiplier;
        this.declaredModelScale = modelScale;
    }

    // ── Контракт підкласу ────────────────────────────────────────────────

    /**
     * Зовнішність: гео-модель, текстура, анімації. За замовчуванням —
     * усе названо за {@link #id}, тож новому маньяку достатньо покласти
     * три файли з правими іменами.
     */
    public ManiacVisuals visuals() {
        return ManiacVisuals.of(id);
    }

    /** Предмет зброї цього маньяка. */
    public abstract Item weaponItem();

    /** Здібності на клавіші 1, 2, 3 — рівно в цьому порядку. */
    public abstract List<Ability> abilities();

    /** Пастки, з яких маньяк обирає до {@code trapsPerMatch} у меню; клавіші 5, 6, 7. */
    public abstract List<TrapArchetype> traps();

    // ── Дефолти балансу: перевизначає архетип, перекриває конфіг ─────────

    /**
     * Дальність удару в блоках. Довгорукий маньяк перевизначає це, щоб
     * його дефолт у власному yml був його власним, а не спільним.
     */
    public double declaredAttackRangeBlocks() { return DEFAULT_ATTACK_RANGE_BLOCKS; }

    /** Перезарядка удару в тіках — дефолт цього маньяка. */
    public int declaredAttackCooldownTicks() { return DEFAULT_ATTACK_COOLDOWN_TICKS; }

    /** Шкода за удар — дефолт цього маньяка. */
    public int declaredAttackDamage() { return DEFAULT_ATTACK_DAMAGE; }

    /**
     * Множник швидкості ходьби відносно ЗВИЧАЙНОЇ ходьби гравця. Маньяк
     * не має спринту взагалі: це і є його «біг» (1.2 = на 20% швидше).
     */
    public double declaredSpeedMultiplier() { return DEFAULT_SPEED_MULTIPLIER; }

    // ── Оголошені кодом значення (для схеми конфігу) ─────────────────────

    /** Ширина хітбокса, оголошена класом. Це дефолт ключа його блока. */
    public final float declaredHitboxWidth()  { return declaredHitboxWidth; }

    /** Висота хітбокса, оголошена класом (див. {@link #DEFAULT_HITBOX_HEIGHT}). */
    public final float declaredHitboxHeight() { return declaredHitboxHeight; }

    /** Висота очей, оголошена класом. */
    public final float declaredEyeHeight()    { return declaredEyeHeight; }

    // ── Що діє ЗАРАЗ: конфіг → код ──────────────────────────────────────

    /** Ширина хітбокса, з якою маньяк ходить у цьому світі. */
    public final float hitboxWidth() {
        return ManiacStats.value(this, ManiacStats.HITBOX_WIDTH).floatValue();
    }

    /** Висота хітбокса, з якою маньяк ходить у цьому світі. */
    public final float hitboxHeight() {
        return ManiacStats.value(this, ManiacStats.HITBOX_HEIGHT).floatValue();
    }

    /**
     * Висота очей. Обрізається висотою хітбокса.
     *
     * Це не косметична дрібниця: очі вище хітбокса дають неузгоджений
     * стан у рушії (камера крізь стелю, рейкаст з-під підлоги). Одним
     * ключем такого стану не заборониш — він залежить від ДВОХ, які
     * адмін править окремо, — тож неможливе поєднання зводиться до
     * можливого на читанні, замість падіння посеред матчу.
     */
    public final float eyeHeight() {
        return Math.min(ManiacStats.value(this, ManiacStats.EYE_HEIGHT).floatValue(), hitboxHeight());
    }

    /** Дальність удару, що діє зараз. */
    public final double attackRangeBlocks() {
        return ManiacStats.value(this, ManiacStats.ATTACK_RANGE).doubleValue();
    }

    /** Перезарядка удару, що діє зараз. */
    public final int attackCooldownTicks() {
        return ManiacStats.value(this, ManiacStats.ATTACK_COOLDOWN).intValue();
    }

    /** Шкода за удар, що діє зараз. */
    public final int attackDamage() {
        return ManiacStats.value(this, ManiacStats.ATTACK_DAMAGE).intValue();
    }

    /** Множник швидкості ходьби, що діє зараз. */
    public final double speedMultiplier() {
        return ManiacStats.value(this, ManiacStats.SPEED).doubleValue();
    }

    // ── Зовнішність: поки що без конфігу ─────────────────────────────────
    // fovMultiplier/modelScale НЕ винесені в yml навмисно: у них немає
    // жодного читача в коді (масштаб моделі має застосовувати рендерер).
    // Ключ без use-site — це мертве налаштування, яке адмін крутить, а
    // нічого не змінюється. Коли рендерер почне їх читати — вони
    // додаються в ManiacStats одним рядком.

    /** Множник FOV камери маньяка (поки що лише оголошене значення). */
    public final float fovMultiplier() { return declaredFovMultiplier; }

    /** Масштаб гео-моделі маньяка (поки що лише оголошене значення). */
    public final float modelScale()    { return declaredModelScale; }

    /**
     * Здібність за номером клавіші 1–3. null, якщо слот порожній —
     * маньяк може мати й одну здібність.
     */
    public final Ability abilityAt(int slot) {
        List<Ability> list = abilities();
        return slot >= 0 && slot < list.size() ? list.get(slot) : null;
    }

    /** Пастка за номером слота 0–2 (5, 6, 7). null, якщо слот порожній. */
    public final TrapArchetype trapAt(int slot) {
        List<TrapArchetype> list = traps();
        return slot >= 0 && slot < list.size() ? list.get(slot) : null;
    }

    /**
     * Перевірка меж. Викликається реєстром при реєстрації — після
     * {@code ManiacStats.ensureRegistered}, тому тут перевіряються
     * ОГОЛОШЕНІ класом числа: діапазони конфігу вже перевірені
     * побудовою його ключів.
     */
    final void validate() {
        if (declaredHitboxWidth <= 0f || declaredHitboxHeight <= 0f) {
            throw new IllegalStateException(id + ": хітбокс " + declaredHitboxWidth + "×" + declaredHitboxHeight
                + " — обидва розміри мають бути додатними.");
        }
        if (declaredEyeHeight <= 0f || declaredEyeHeight > declaredHitboxHeight) {
            // Очі вище хітбокса — це не «дивно», це фізично неможливо
            // застосувати: EntityEvent.Size і EntityEvent.EyeHeight дають
            // неузгоджений стан (видно крізь стелю/з-під підлоги).
            throw new IllegalStateException(id + ": висота очей " + declaredEyeHeight
                + " поза межами хітбокса " + declaredHitboxHeight + ".");
        }
        if (abilities().size() > MAX_ABILITIES) {
            throw new IllegalStateException(id + ": здібностей "
                + abilities().size() + ", а клавіш лише " + MAX_ABILITIES + " (1/2/3).");
        }
        if (traps().size() > MAX_TRAP_SLOTS) {
            throw new IllegalStateException(id + ": пасток "
                + traps().size() + ", а клавіш лише " + MAX_TRAP_SLOTS + " (5/6/7).");
        }
    }

    public final String id()            { return id; }
    public final String displayName()   { return displayName; }
}
