package com.log_to_kot.maniacmod.client.renderer.maniac;

import com.log_to_kot.maniacmod.maniacs.ManiacAnimationSet;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Гео-аниматабл маньяка + СТЕЙТ-МАШИНА анімацій.
 *
 * ── Головна ідея: анімації перемикає КОД, не гравець ─────────────────
 * У грі немає жодної кнопки «грати анімацію». Рушій (GeckoLib) раз на
 * кадр питає в цього класу «що зараз грати», а клас відповідає сам,
 * дивлячись на стан гравця. Тому анімація не може «застрягти» чи
 * «підводити» — вона завжди обчислюється з поточного стану.
 *
 * ── Чому немає дьоргання (головна вимога) ───────────────────────────
 * Три правила, яких тут дотримано:
 *   1. Рішення — ЧИСТА функція від стану гравця (жодних «прапорців
 *      анімації», які треба не забути скинути). Однаковий стан ⇒
 *      однакова анімація, кожен кадр.
 *   2. {@code setAndContinue} з тим САМИМ {@link RawAnimation} не
 *      перезапускає анімацію з нуля — GeckoLib порівнює отримане
 *      значення з поточним і нічого не робить, якщо воно те саме. Тому
 *      ходьба не «сіпається» щокадру.
 *   3. {@code RawAnimation} кешуються за іменем: одне й те саме ім'я —
 *      один і той самий об'єкт, тож порівняння в (2) завжди працює.
 *      Якби створювати новий {@code RawAnimation} щокадру, порівняння
 *      було б за вмістом (повільніше) і будь-яка неточність дала б
 *      перезапуск — саме те «дрижання», якого треба уникнути.
 *
 * ── Пріоритет станів ────────────────────────────────────────────────
 * Перехід між станами супроводжує {@link #TRANSITION_TICKS} тіків
 * змішування (GeckoLib інтерполює сам), тому навіть різка зміна
 * (біг → стрибок) виглядає плавно.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacAnimatable implements GeoAnimatable {

    /** Скільки тіків змішуються дві анімації при переході стану. */
    private static final int TRANSITION_TICKS = 3;

    /** Скільки тіків тримається одноразова анімація удару. */
    private static final int ATTACK_TICKS = 8;

    /** Швидкість ходьби, вище якої гравець вважається «рухається». */
    private static final float MOVING_SPEED = 0.02f;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // ── Контекст поточного рендера ──────────────────────────────────────
    // Один екземпляр аниматабла на клієнт (так вимагає GeoReplacedEntityRenderer),
    // а рендер завжди послідовний — тому «хто зараз малюється» можна
    // тримати в полях, а не тягнути через увесь ланцюг GeckoLib.

    private static Player renderedPlayer;
    private static ManiacArchetype renderedArchetype;
    private static long tick;

    /** Гравець, для якого зараз малюється модель (потрібен моделі й контролеру). */
    private static Player lastPlayer;

    /** Чи гравець замахувався на попередньому кадрі — щоб зловити фронт. */
    private static boolean wasSwinging;

    /** Тік, до якого дограється одноразова анімація удару. */
    private static long attackUntilTick = Long.MIN_VALUE;

    /** Створюється рендерером ({@code ManiacGeoRenderer}), один раз на клієнт. */
    ManiacAnimatable() {}

    /** Викликається {@code ManiacRenderHandler} ПЕРЕД малюванням моделі. */
    static void begin(Player player, ManiacArchetype archetype, long gameTick) {
        if (player != lastPlayer) {
            // Інший маньяк — стан замаху не має «протікати» між ними.
            lastPlayer = player;
            wasSwinging = player.swinging;
            attackUntilTick = Long.MIN_VALUE;
        }
        if (player.swinging && !wasSwinging) {
            attackUntilTick = gameTick + ATTACK_TICKS;
        }
        wasSwinging = player.swinging;

        renderedPlayer = player;
        renderedArchetype = archetype;
        tick = gameTick;
    }

    /** Викликається ПІСЛЯ малювання — контекст більше не дійсний. */
    static void end() {
        renderedPlayer = null;
        renderedArchetype = null;
    }

    static Player renderedPlayer()        { return renderedPlayer; }
    static ManiacArchetype archetype()    { return renderedArchetype; }

    /** Гео-модель бере звідси свій набір анімацій та імена для {@code RawAnimation}. */
    static ManiacAnimationSet animations(ManiacArchetype archetype) {
        return archetype.visuals().animationSet();
    }

    // ── Контролер ───────────────────────────────────────────────────────

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "maniac_body", TRANSITION_TICKS, this::controller));
    }

    private PlayState controller(AnimationState<ManiacAnimatable> state) {
        Player player = renderedPlayer;
        ManiacArchetype archetype = renderedArchetype;
        if (player == null || archetype == null) return PlayState.STOP;

        ManiacAnimationSet animations = animations(archetype);
        // Пріоритет: удар → взаємодія → повітря → присідання → біг → ходьба → стояння.
        // Порядок саме такий: дія важливіша за переміщення, а присідання —
        // за темп руху (інакше sneak_walk ніколи б не показувався, бо
        // присідаючи гравець теж «рухається»).
        boolean oneShot;
        String name;
        if (tick < attackUntilTick) {
            name = animations.attack();
            oneShot = true;
        } else if (player.isUsingItem()) {
            name = animations.interact();
            oneShot = true;
        } else if (!player.onGround()) {
            name = animations.jump();
            oneShot = false;
        } else {
            boolean crouching = player.isShiftKeyDown();
            boolean moving = player.walkAnimation.speed() > MOVING_SPEED;
            if (crouching) {
                name = moving ? animations.sneakWalk() : animations.sneak();
            } else if (moving) {
                name = player.isSprinting() ? animations.run() : animations.walk();
            } else {
                name = animations.idle();
            }
            oneShot = false;
        }

        return state.setAndContinue(oneShot ? play(name) : loop(name));
    }

    // ── Кеш RawAnimation ────────────────────────────────────────────────
    // Ключ — саме ім'я анімації: два архетипи з однаковим іменем діляться
    // об'єктом, і це правильно (RawAnimation не залежить від архетипу).

    private static final Map<String, RawAnimation> LOOPS = new ConcurrentHashMap<>();
    private static final Map<String, RawAnimation> ONE_SHOTS = new ConcurrentHashMap<>();

    private static RawAnimation loop(String name) {
        return LOOPS.computeIfAbsent(name, n -> RawAnimation.begin().thenLoop(n));
    }

    private static RawAnimation play(String name) {
        return ONE_SHOTS.computeIfAbsent(name, n -> RawAnimation.begin().thenPlay(n));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    /**
     * Час анімації. Береться з ігрового тіка, а не з реального часу: так
     * анімація зупиняється разом із грою (пауза, меню) і не «стрибає»
     * вперед після повернення.
     */
    @Override
    public double getTick(Object animatable) {
        return tick;
    }
}
