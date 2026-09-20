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
 * Один GeckoLib-контролер: "idle" (генератор ще не повністю
 * полагоджений — REPAIR чи FUEL стадія) і "active" (обидві стадії
 * пройдено, {@code GeneratorPoi.isCompleted()}). Перемикання керується
 * {@link #ACTIVE} — синхронізованим прапорцем, який виставляє
 * {@link #syncFromPoi()}, викликаний тіком самої сутності (щотік читає
 * актуальний {@code GeneratorPoi.isCompleted()} за своєю позицією) —
 * так рендер завжди узгоджений із сервером, а не з власним локальним
 * станом, що міг би розійтися при переспавні.
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

    /** Поворот по Y. Завжди 0 — константа, не синхронізується (як у GroundItemEntity). */
    public static final float ROTATION_Y = 0f;

    private static final String CONTROLLER_NAME = "generatorController";
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation ANIM_ACTIVE = RawAnimation.begin().thenLoop("active");

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
        boolean exploding = poi.explosionTicksLeft() > 0;
        if (exploding != explosionGlow) {
            setExplosionGlow(exploding);
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
        if (!level().isClientSide && explosionGlow) {
            setExplosionGlow(false);
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
        controllers.add(new AnimationController<>(this, CONTROLLER_NAME, 0,
            state -> state.setAndContinue(isActive() ? ANIM_ACTIVE : ANIM_IDLE)));
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
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("RotationX", this.entityData.get(ROTATION_X));
        tag.putBoolean("Active", this.entityData.get(ACTIVE));
        tag.putBoolean("FuelStage", this.entityData.get(FUEL_STAGE));
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
