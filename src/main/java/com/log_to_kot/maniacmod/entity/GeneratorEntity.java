package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.match.MatchRuntimeRegistry;
import com.log_to_kot.maniacmod.map.zones.GeneratorPoi;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.network.NetworkHooks;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Сутність генератора.
 *
 * ── Чому сутність, а не блок ─────────────────────────────────────────
 * Раніше генератор був блоком, який картобудівник мусив ставити руками
 * на кожній карті заздалегідь, а його «готовий» стан ніколи не
 * вмикався кодом. Сутність натомість САМА з'являється в потрібних точках при старті
 * матчу ({@code MatchOrchestrator.applySpawnPlan}), як і
 * {@link GroundItemEntity}, і сама відповідає за свою анімацію.
 *
 * ── Хітбокс і поворот ────────────────────────────────────────────────
 * Хітбокс — квадрат 1.2×1.2 блока (див. {@code ModEntityTypes.GENERATOR}).
 * Поворот по X — випадковий, задається один раз при спавні; по Y —
 * завжди 0 (той самий підхід, що {@link GroundItemEntity} — випадковий
 * X не дає однаковим генераторам поруч виглядати копіями, нульовий Y
 * не дає моделі "лягти боком").
 *
 * ── Дві стадії, одна модель ──────────────────────────────────────────
 * Уся ігрова логіка (ремонт 0-100%, міні-ігри, залив бензину 200% у
 * дві каністри по 100%) живе в {@link GeneratorPoi} — записі карти за
 * позицією ({@code MatchOrchestrator.generatorAt(pos)}), а НЕ в цій
 * сутності. Сутність — суто "тіло" в світі: показує модель/анімацію
 * та є ціллю для рейкасту утримання Shift+ПКМ (ремонт/залив), а всю
 * бухгалтерію прогресу делегує {@code GeneratorModule} через
 * {@link #generatorPos()}. Так сутність можна знищити й переспавнити
 * (наприклад при відновленні матчу) без втрати прогресу генератора —
 * прогрес живе окремо, прив'язаний до позиції, а не до конкретного
 * екземпляра сутності.
 *
 * ── Анімація ─────────────────────────────────────────────────────────
 * Один GeckoLib-контролер, три анімації з {@code generator.animation.json}:
 * "idle" (генератор ще не повністю полагоджений — REPAIR чи FUEL
 * стадія), "running" (обидві стадії пройдено, {@code
 * GeneratorPoi.isCompleted()}) і одноразовий "boom" на момент вибуху
 * (після нього контролер сам повертається на "idle"). Перемикання
 * керується {@link #ACTIVE}/{@link #EXPLODING} — синхронізованими
 * прапорцями, які виставляє {@link #syncFromPoi()}, викликаний тіком
 * самої сутності (щотік читає актуальний {@code
 * GeneratorPoi.isCompleted()}/{@code explosionTicksLeft()} за своєю
 * позицією) — так рендер завжди узгоджений із сервером, а не з власним
 * локальним станом, що міг би розійтися при переспавні.
 */
public class GeneratorEntity extends Entity implements GeoEntity {

    /** Поворот по X. Випадковий, задається при спавні. */
    private static final EntityDataAccessor<Float> ROTATION_X =
        SynchedEntityData.defineId(GeneratorEntity.class, EntityDataSerializers.FLOAT);

    /** true, коли обидві стадії (REPAIR і FUEL) пройдено — грає "active" анімацію. */
    private static final EntityDataAccessor<Boolean> ACTIVE =
        SynchedEntityData.defineId(GeneratorEntity.class, EntityDataSerializers.BOOLEAN);

    /**
     * true, коли генератор уже пройшов REPAIR і зараз чекає на залив
     * (стадія FUEL), false — ще на стадії REPAIR (або вже DONE, але
     * тоді {@link #ACTIVE} однаково true й це поле клієнту не потрібне).
     * Синхронізується так само, як {@link #ACTIVE}, у {@link #syncFromPoi()}
     * — потрібне {@code GeneratorHintOverlay}, щоб показати правильний
     * текст підказки (ремонт проти "потрібна каністра"), не вгадуючи
     * стадію на клієнті і не ганяючи для цього окремий пакет.
     */
    private static final EntityDataAccessor<Boolean> FUEL_STAGE =
        SynchedEntityData.defineId(GeneratorEntity.class, EntityDataSerializers.BOOLEAN);

    /**
     * БАГФІКС: прогрес ПОТОЧНОЇ стадії, 0-100, нормалізований так само,
     * як {@code GeneratorProgressPacket.stagePercent()} — для REPAIR це
     * {@code GeneratorPoi.repairPercent()}, для FUEL — частка залитого
     * від {@code fuelRequiredPercent}.
     *
     * ── Навіщо, якщо прогрес уже йде окремим пакетом ──────────────────
     * {@code GeneratorProgressPacket} шле сервер лише тому гравцю, який
     * САМ зараз тримає утримання (в момент refreshRepair/refreshFuel).
     * Хтось інший, хто просто ДИВИТЬСЯ на генератор (наприклад, підійшов
     * і ще не встиг натиснути Shift+ПКМ, або вже відпустив, поки хтось
     * інший продовжує заливати), про реальний накопичений прогрес нічого
     * не знав — {@code GeneratorHintOverlay} завжди малював порожній
     * (0%) бар у підказці "Потрібна каністра бензину"/"Утримуй Shift+ПКМ",
     * навіть якщо бак уже залито наполовину. Це синхронізоване поле —
     * той самий підхід, що {@link #ACTIVE}/{@link #FUEL_STAGE}: щотік
     * читається з {@link GeneratorPoi} у {@link #syncFromPoi()} і
     * бачиться УСІМ клієнтам, що відстежують сутність, незалежно від
     * того, хто саме зараз ремонтує.
     */
    private static final EntityDataAccessor<Integer> STAGE_PERCENT =
        SynchedEntityData.defineId(GeneratorEntity.class, EntityDataSerializers.INT);

    /**
     * true на тіках, коли триває вибух ({@code GeneratorPoi.explosionTicksLeft() > 0}).
     * Синхронізується так само, як {@link #ACTIVE}/{@link #FUEL_STAGE} — потрібне
     * клієнтському аніматору, щоб програвати одноразовий {@code "boom"}, а не лише
     * серверному {@code setExplosionGlow}, який керує лише контуром.
     */
    private static final EntityDataAccessor<Boolean> EXPLODING =
        SynchedEntityData.defineId(GeneratorEntity.class, EntityDataSerializers.BOOLEAN);

    /**
     * true, поки на стадії FUEL хтось РЕАЛЬНО тримає заливку (заряджена
     * каністра в руці, кнопка затиснута). Виставляється в
     * {@link #syncFromPoi()} з {@code GeneratorPoi#isFillingThisTick()} —
     * прямого прапора з того місця, де заряд каністри фактично списується,
     * а не з приросту округленого {@link #stagePercent()}.
     * {@code registerControllers} читає це поле, щоб показувати
     * "idle_fuel" увесь час утримання й повертати "idle" лише після того,
     * як гравець відпустив кнопку.
     */
    private static final EntityDataAccessor<Boolean> FILLING =
        SynchedEntityData.defineId(GeneratorEntity.class, EntityDataSerializers.BOOLEAN);

    /**
     * Скільки тіків після ОСТАННЬОГО реального тіку заливу ще тримати
     * {@link #FILLING}. Не залежить від темпу заливу: джерело правди —
     * {@code GeneratorPoi#isFillingThisTick()}, який true на КОЖНОМУ тіку
     * утримання, а не лише на тих, де відсоток виріс. Тому запас потрібен
     * лише на порядок тіків сервера та мережевий дрейф, а не на діру
     * Бресенхема.
     */
    private static final int FILLING_RELEASE_GRACE_TICKS = 2;

    /** Скільки тіків ще тримати FILLING після останнього реального тіку заливу (лише сервер, не синхронізується). */
    private int fillingGraceLeft = 0;


    /** Поворот по Y. Завжди 0 — константа, не синхронізується (як у GroundItemEntity). */
    public static final float ROTATION_Y = 0f;

    /**
     * Радіус (блоки), у якому {@code client.GeneratorLoopClientSoundHandler}
     * тримає клієнтський гул (свій радіус — {@code LOOP_RADIUS_BLOCKS}
     * там); звук заливу ({@code client.GeneratorFuelClientSoundHandler})
     * не має радіуса взагалі — грає лише для гравця, який сам зараз
     * заливає. Лишена тут як довідкова межа для решти клієнтського коду
     * (підсвітка, оверлеї), який досі орієнтується на "чи генератор
     * взагалі поруч".
     */
    public static final double SOUND_TRACK_RANGE = 24.0;

    private static final String CONTROLLER_NAME = "generatorController";
    /** До полагодження, стадія REPAIR — зациклена "спокійна" анімація. */
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("idle");
    /** Стадія FUEL, поки хтось РЕАЛЬНО ліє заряджену каністру ({@link #isFilling()}) — окрема поза. */
    private static final RawAnimation ANIM_IDLE_FUEL = RawAnimation.begin().thenLoop("idle_fuel");
    /** Після полагодження (isActive()==true) — зациклена анімація роботи генератора. */
    private static final RawAnimation ANIM_ACTIVE = RawAnimation.begin().thenLoop("running");
    /** На момент вибуху — одноразовий "boom", потім знову зациклений idle. */
    private static final RawAnimation ANIM_BOOM =
        RawAnimation.begin().thenPlay("boom").thenLoop("idle");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public GeneratorEntity(EntityType<? extends GeneratorEntity> type, Level level) {
        super(type, level);
    }

    /**
     * Створює й реєструє генератор у світі. Викликається зі
     * {@code MatchOrchestrator.applySpawnPlan} для кожної точки
     * розмітки картобудівника (замість колишнього очікування вже
     * поставленого блоку).
     *
     * @param rotationX випадковий поворот по X — генерується викликачем
     *                  так само, як {@code loot.GroundItemPlacement}
     *                  генерує його для предметів.
     */
    public static GeneratorEntity spawn(EntityType<GeneratorEntity> type, Level level,
                                        double x, double y, double z, float rotationX) {
        GeneratorEntity entity = new GeneratorEntity(type, level);
        entity.entityData.set(ROTATION_X, rotationX % 360f);
        entity.moveTo(x, y, z, ROTATION_Y, 0f);
        MatchRuntimeRegistry.register(entity);

        // БАГ (генератори не з'являлись, хоч точки розмічені): раніше метод
        // закінчувався на register(). А register() лише ставить NBT-маркер
        // "це сутність матчу" (для очищення) — у світ вона її НЕ додає.
        // Без addFreshEntity сутність існувала лише як Java-об'єкт, який
        // ніхто не тримав, тож генератор жодного разу не з'явився фізично,
        // хоч логічний запис GeneratorPoi (ремонт, прогрес) створювався.
        if (!level.isClientSide && !level.addFreshEntity(entity)) {
            ManiacMod.LOGGER.warn("[generator] сутність не додалась у світ на ({}, {}, {}) — "
                + "генератор буде лише логічним записом без тіла", x, y, z);
        }
        return entity;
    }

    // ── Дані ─────────────────────────────────────────────────────────────

    @Override
    protected void defineSynchedData() {
        this.entityData.define(ROTATION_X, 0f);
        this.entityData.define(ACTIVE, false);
        this.entityData.define(FUEL_STAGE, false);
        this.entityData.define(STAGE_PERCENT, 0);
        this.entityData.define(EXPLODING, false);
        this.entityData.define(FILLING, false);
    }

    public float rotationX() {
        return this.entityData.get(ROTATION_X);
    }

    public boolean isActive() {
        return this.entityData.get(ACTIVE);
    }

    /** true — генератор чекає на залив (стадія FUEL), REPAIR уже пройдено. */
    public boolean isFuelStage() {
        return this.entityData.get(FUEL_STAGE);
    }

    /**
     * БАГФІКС: реальний прогрес поточної стадії, 0-100 — для FUEL це
     * фактично залитий відсоток бака (не залежить від того, хто зараз
     * тримає ПКМ). Читається {@code GeneratorHintOverlay}, щоб бар
     * підказки показував справжній стан, а не завжди порожній.
     */
    public int stagePercent() {
        return this.entityData.get(STAGE_PERCENT);
    }

    /** true під час вибуху — {@code registerControllers} програє за цим {@link #ANIM_BOOM}. */
    public boolean isExploding() {
        return this.entityData.get(EXPLODING);
    }

    /** true — прогрес FUEL реально росте зараз (див. {@link #FILLING} докстрінг). */
    public boolean isFilling() {
        return this.entityData.get(FILLING);
    }

    /** Позиція, за якою {@code GeneratorModule}/{@code MatchOrchestrator} шукають {@link GeneratorPoi}. */
    public net.minecraft.core.BlockPos generatorPos() {
        return blockPosition();
    }

    // ── Тік: синхронізація ACTIVE з логічним станом ────────────────────────

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        syncFromPoi();
        // Гул роботи (generator_loop) і звук заливу (fuel_fill) тепер
        // повністю клієнтські — client.GeneratorLoopClientSoundHandler /
        // client.GeneratorFuelClientSoundHandler читають уже
        // синхронізований стан напряму, без окремих серверних лупів.
    }

    /**
     * Раз на тік перечитує стан {@link GeneratorPoi} за своєю позицією
     * й оновлює синхронізовані прапорці: немає окремого «виклику
     * активації», сутність сама щотік перевіряє свій стан.
     */
    private void syncFromPoi() {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        GeneratorPoi poi = match.generatorAt(generatorPos());
        if (poi == null) {
            // Сирота: у поточному матчі на цій позиції генератора немає.
            // Це залишок попереднього матчу або краху сервера — сутність
            // збережена разом із чанком і "ожила", коли той завантажився.
            // Очищення при скиданні матчу (MatchRuntimeRegistry.cleanup)
            // бачить лише ЗАВАНТАЖЕНІ сутності, тож генератор у далекому
            // чанку його переживає. Без цього рядка на карті лишався б
            // видимий, але неробочий генератор поруч зі справжнім.
            //
            // Безпечно для живих генераторів: applySpawnPlan додає
            // GeneratorPoi ДО того, як створює сутність, тож у
            // справжнього генератора запис завжди вже існує на його
            // першому тіку.
            discard();
            return;
        }
        boolean completed = poi.isCompleted();
        if (completed != isActive()) {
            this.entityData.set(ACTIVE, completed);
        }
        boolean fuelStage = poi.stage() == GeneratorPoi.Stage.FUEL;
        if (fuelStage != isFuelStage()) {
            this.entityData.set(FUEL_STAGE, fuelStage);
        }

        // БАГФІКС: той самий нормалізований 0-100 відсоток поточної стадії,
        // що рахує GeneratorProgressPacket — для FUEL ділимо сирий
        // fuelPercent на fuelRequiredPercent (може бути != 200), для
        // REPAIR readPercent уже 0-100. DONE лишає останнє значення (100).
        int stagePercent;
        if (poi.stage() == GeneratorPoi.Stage.FUEL) {
            int required = GeneratorPoi.fuelRequiredPercent();
            stagePercent = required <= 0 ? 0
                : (int) Math.min(100L, (long) poi.fuelPercent() * 100L / required);
        } else if (poi.stage() == GeneratorPoi.Stage.DONE) {
            stagePercent = 100;
        } else {
            stagePercent = poi.repairPercent();
        }
        if (stagePercent != stagePercent()) {
            this.entityData.set(STAGE_PERCENT, stagePercent);
        }

        // "idle_fuel" грає ВЕСЬ час, поки хтось РЕАЛЬНО тримає заливку, і
        // гасне лише тоді, коли гравець відпустив кнопку (або каністра
        // спорожніла / він відійшов).
        //
        // Джерело правди — GeneratorPoi.isFillingThisTick(): прапор, який
        // GeneratorModule.tickFuel виставляє на КОЖНОМУ тіку, де залив
        // справді йде (заряджена каністра в руці, сесія жива, стадія FUEL).
        // Раніше тут вгадували залив за приростом округленого stagePercent
        // (+ grace) — але приріст іде Бресенхемом, «дірами» по кілька тіків,
        // а сам відсоток ще й округлюється вниз, тож будь-яке grace-вікно
        // рано чи пізно було коротшим за діру і idle_fuel зривалась в idle
        // посеред безперервної заливки.
        //
        // Grace лишається, але лише на 1-2 тіки — щоб перекрити одиничний
        // розрив між порядком тіків GeneratorModule і цієї сутності (порядок
        // обходу сутностей/фаз не гарантований) та мережевий дрейф
        // FILLING-синхронізації. Це не «вгадування», а згладжування.
        boolean filling;
        if (!fuelStage) {
            fillingGraceLeft = 0;
            filling = false;
        } else if (poi.isFillingThisTick()) {
            fillingGraceLeft = FILLING_RELEASE_GRACE_TICKS;
            filling = true;
        } else {
            fillingGraceLeft = Math.max(0, fillingGraceLeft - 1);
            filling = fillingGraceLeft > 0;
        }
        if (filling != isFilling()) {
            this.entityData.set(FILLING, filling);
        }

        boolean exploding = poi.explosionTicksLeft() > 0;
        if (exploding != explosionGlow) {
            setExplosionGlow(exploding);
        }
        if (exploding != isExploding()) {
            this.entityData.set(EXPLODING, exploding);
        }
    }

    // ── Вибух: червоний контур ───────────────────────────────────────────

    /** Назва команди, чий колір контуру — червоний. Спільна для всіх генераторів. */
    private static final String EXPLOSION_TEAM = "maniac_gen_explosion";

    /** Чи зараз увімкнено червоний контур вибуху (лише сервер). */
    private boolean explosionGlow = false;

    /**
     * Вмикає/вимикає червоний контур генератора на час вибуху.
     *
     * Ванільне світіння ({@code setGlowingTag}) малює контур крізь стіни
     * КОЖНОМУ гравцеві, що бачить сутність, — включно з маньяком, без
     * жодного клієнтського коду. Колір контуру береться з кольору
     * команди сутності, тож генератор на ці секунди входить у спільну
     * червону команду. Далеко за межами відстеження сервера сутності в
     * клієнта немає взагалі, там працює екранний маркер
     * ({@code GeneratorExplosionPacket}).
     */
    private void setExplosionGlow(boolean on) {
        explosionGlow = on;
        setGlowingTag(on);

        Scoreboard scoreboard = level().getScoreboard();
        String member = getScoreboardName();
        PlayerTeam team = scoreboard.getPlayerTeam(EXPLOSION_TEAM);
        if (on) {
            if (team == null) {
                team = scoreboard.addPlayerTeam(EXPLOSION_TEAM);
                team.setColor(ChatFormatting.RED);
            }
            scoreboard.addPlayerToTeam(member, team);
        } else if (team != null && scoreboard.getPlayersTeam(member) == team) {
            scoreboard.removePlayerFromTeam(member, team);
        }
    }

    /**
     * Прибираємо запис із команди разом із сутністю: скидання матчу під
     * час вибуху інакше лишило б у збереженні скорборду «мертвий» UUID.
     */
    @Override
    public void remove(Entity.RemovalReason reason) {
        if (!level().isClientSide) {
            if (explosionGlow) {
                setExplosionGlow(false);
            }
        }
        super.remove(reason);
    }

    /**
     * Генератор — ключовий об'єкт гри й має бути видимим настільки
     * далеко, наскільки його узагалі відстежує сервер (див.
     * {@code ModEntityTypes.GENERATOR}); ванільна відстань малювання
     * для сутності з хітбоксом 1.2 — лише кілька десятків блоків.
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return true;
    }

    // ── Взаємодія ────────────────────────────────────────────────────────

    /**
     * ПКМ по генератору. Сама сутність нічого не робить.
     *
     * Маньяк генератор не чіпає взагалі: механіки саботажу в грі немає.
     * Єдиний спосіб зашкодити ремонту — не допустити виживих (і вибух
     * генератора при провалі міні-гри, див. {@code GeneratorPoi.explode}).
     *
     * Ремонт і залив бензину теж НЕ тут: {@code Entity.interact()} у
     * ванілі спрацьовує один раз на клік, а для «тримай, поки не
     * набереться» потрібен стан утримання. Клієнт відстежує Shift+ПКМ
     * ({@code ManiacKeybinds.isRepairHeld()}) і шле
     * {@code GeneratorRepairHoldPacket} лише на зміну стану — так само,
     * як підняття непритомного йде через {@code RescueHoldPacket}.
     * Сервер сам розрізняє стадію за {@code GeneratorPoi.stage()}.
     *
     * Повертаємо PASS, щоб ванільний ланцюг взаємодії відпрацював далі.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return level().isClientSide ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** Невразливий, як і колишній блок (руйнується лише разом зі скиданням матчу, не уроном). */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    // ── GeckoLib ─────────────────────────────────────────────────────────

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, CONTROLLER_NAME, 5,
            state -> state.setAndContinue(
                isExploding() ? ANIM_BOOM
                    : isActive() ? ANIM_ACTIVE
                    : isFilling() ? ANIM_IDLE_FUEL
                    : ANIM_IDLE)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // ── Серіалізація ─────────────────────────────────────────────────────
    //
    // Сама сутність нічого не зберігає, крім вигляду (поворот, активність) —
    // ігровий прогрес (repairPercent/fuelPercent/стадія) живе в
    // GeneratorPoi/MatchOrchestrator і серіалізується разом зі станом
    // матчу, а не сутністю. Генератори й так пересоздаються при кожному
    // застосуванні плану спавну, тому переживати чанк-релоад/сейв-лоад
    // сутності немає сенсу — так само, як GroundItemEntity нічого не
    // зберігає окрім свого власного вигляду.

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(ROTATION_X, tag.getFloat("RotationX"));
        this.entityData.set(ACTIVE, tag.getBoolean("Active"));
        this.entityData.set(FUEL_STAGE, tag.getBoolean("FuelStage"));
        this.entityData.set(STAGE_PERCENT, tag.getInt("StagePercent"));
        this.entityData.set(EXPLODING, tag.getBoolean("Exploding"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("RotationX", this.entityData.get(ROTATION_X));
        tag.putBoolean("Active", this.entityData.get(ACTIVE));
        tag.putBoolean("FuelStage", this.entityData.get(FUEL_STAGE));
        tag.putInt("StagePercent", this.entityData.get(STAGE_PERCENT));
        tag.putBoolean("Exploding", this.entityData.get(EXPLODING));
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
