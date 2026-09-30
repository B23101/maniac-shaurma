package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.anim.BoneClip;
import com.log_to_kot.maniacmod.client.anim.BoneClipLoader;
import com.log_to_kot.maniacmod.items.FlashlightItem;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Поза «поранений» для виживого з поламаною ногою ({@link SurvivorState#BROKEN_LEG}).
 *
 * <p>Поза береться з {@code assets/maniacmod/player_animations/wounded.json}
 * (Blockbench), значення 1:1 як у {@link HandPoseTracks}: кути — градуси,
 * позиції — одиниці {@code ModelPart}. Змінив файл у Blockbench — після
 * {@code F3+T} нова поза одразу в грі, код правити не треба. Якщо файлу
 * немає — діють вбудовані значення, що дорівнюють поточному JSON.
 *
 * <h3>Чому руки «не розвалюються» під час ходьби</h3>
 * Ванільний {@code setupAnim} гойдає руки від {@code limbSwing}. Ми
 * викликаємось у {@code setupAnim TAIL} ({@code MixinWoundedPose}) і
 * ПЕРЕПИСУЄМО кути обох рук цільовими, а не додаємо до них — тому гойдання
 * ходьби просто немає. Ноги ми не чіпаємо взагалі: ванільна хода лишається.
 *
 * <h3>Що чіпаємо і як</h3>
 * <ul>
 *   <li><b>Руки</b> — абсолютний кут (lerp від ванільного до цілі за вагою
 *       пози), позиція додається: ванільний {@code setupAnim} скидає
 *       {@code arm.x/y/z} щокадру, тож накопичення немає.</li>
 *   <li><b>Голова / торс</b> — кут ДОДАЄТЬСЯ до ванільного (голова
 *       продовжує дивитись, куди дивиться гравець, просто нахилена вперед).
 *       {@code x/z} — АБСОЛЮТНІ: ванільний код не скидає {@code head/body.x/z},
 *       а модель одна на всіх гравців (див. {@link #restorePersistentBase}).</li>
 *   <li><b>Ноги</b> — не чіпаємо.</li>
 * </ul>
 *
 * <h3>Плавність</h3>
 * Вага пози (0..1) наростає/спадає за {@link #BLEND_SECONDS} за реальним
 * часом (не за тіками), окремо для голови/торса, правої і лівої руки. Тому:
 * шина — поза плавно відпускає; підбір предмета/ремонт генератора —
 * рука плавно віддає керування і так само повертається в позу.
 *
 * <h3>Пріоритет (хто виграє руку)</h3>
 * Ремонт/заправка генератора, підбір предмета, ліхтар у руці та
 * використання предмета (аптечка, шина) мають свої пози — для ЦІЄЇ руки
 * wounded відступає. Друга рука лишається в wounded. Порядок міксинів у
 * {@code maniacmod.mixins.json} тут не важливий: ми самі не ліземо в руку,
 * яку хтось веде.
 *
 * <h3>Не діє</h3>
 * Лежачи/повзком ({@code isProne} — там {@code downed_lie}), у першій особі
 * для локального гравця (руку від першої особи малює той самий
 * {@code setupAnim}, і зайвий yaw/roll зіпсував би її), на транспорті,
 * у плаванні/польоті/сні, у фазі пробудження.
 * Під час присідання чіпаємо лише руки й голову — нахил торса вже дає
 * ванільне присідання, подвійний нахил ламав би позу.
 *
 * <p>Чисто косметика: нічого не рахує в матчі, стан бере з
 * {@link ClientMatchState#survivorStateOf}.
 */
@OnlyIn(Dist.CLIENT)
public final class WoundedPose {

    /** {@code assets/maniacmod/player_animations/wounded.json}. */
    public static final ResourceLocation CLIP_FILE =
        new ResourceLocation(ManiacMod.MOD_ID, "player_animations/wounded.json");

    /** За скільки секунд поза повністю входить/виходить. */
    private static final float BLEND_SECONDS = 0.30f;
    /** Стеля кута голови: дивишся вниз на 90° + нахил пози не має встромляти голову в груди. */
    private static final float MAX_HEAD_PITCH = (float) (Math.PI / 2.0);

    private static final int HEAD = 0, BODY = 1, R_ARM = 2, L_ARM = 3;
    private static final String[] BONES = {
        BoneClip.HEAD, BoneClip.BODY, BoneClip.RIGHT_ARM, BoneClip.LEFT_ARM
    };

    /** Ціль пози: градуси / одиниці ModelPart; {@code has*} = кістка є у файлі. */
    private static final class Target {
        final float[][] rot = new float[4][3];
        final float[][] pos = new float[4][3];
        final boolean[] hasRot = new boolean[4];
        final boolean[] hasPos = new boolean[4];
    }

    /** Кеш пози; {@code null} — ще не читали або файл перезавантажили. */
    private static Target cached;

    /** Ваги пози одного гравця. */
    private static final class Blend {
        float body, right, left;
        long lastNanos;
    }

    private static final Map<UUID, Blend> BLENDS = new HashMap<>();

    private WoundedPose() {}

    // ── Завантаження пози ────────────────────────────────────────────────

    private static Target target() {
        Target p = cached;
        if (p != null) return p;
        p = new Target();
        boolean loaded = false;
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(CLIP_FILE);
            if (res.isPresent()) {
                try (Reader reader = res.get().openAsReader()) {
                    BoneClip clip = BoneClipLoader.load(reader, null,
                        w -> ManiacMod.LOGGER.warn("[ManiacMod] wounded.json: {}", w));
                    float[] v = new float[3];
                    for (int i = 0; i < BONES.length; i++) {
                        if (clip.sample(BONES[i], BoneClip.Channel.ROTATION, 0f, v)) {
                            System.arraycopy(v, 0, p.rot[i], 0, 3);
                            p.hasRot[i] = true;
                        }
                        if (clip.sample(BONES[i], BoneClip.Channel.POSITION, 0f, v)) {
                            System.arraycopy(v, 0, p.pos[i], 0, 3);
                            p.hasPos[i] = true;
                        }
                    }
                    loaded = true;
                    ManiacMod.LOGGER.info("[ManiacMod] wounded: кістки {}", clip.boneNames());
                }
            } else {
                ManiacMod.LOGGER.info("[ManiacMod] {} не знайдено — вбудована поза wounded", CLIP_FILE);
            }
        } catch (IOException | RuntimeException e) {
            ManiacMod.LOGGER.error("[ManiacMod] не вдалося прочитати {}: {}", CLIP_FILE, e.toString());
        }
        if (!loaded) fillBuiltIn(p);
        cached = p;
        return p;
    }

    /** Ті самі числа, що зараз у wounded.json (запасний варіант). */
    private static void fillBuiltIn(Target p) {
        set(p, HEAD,  22.5f, 0f, 0f,               0f, 0f, -2f);
        set(p, BODY,  12.5f, 0f, 0f,               0f, 0f, -2.25f);
        set(p, R_ARM, -3.3008f, -55.6655f, -49.9423f, 0f, -2f, -3f);
        set(p, L_ARM, 2.5f, 0f, 0f,                0f, 0f, -3f);
    }

    private static void set(Target p, int i, float rx, float ry, float rz, float px, float py, float pz) {
        p.rot[i][0] = rx; p.rot[i][1] = ry; p.rot[i][2] = rz;
        p.pos[i][0] = px; p.pos[i][1] = py; p.pos[i][2] = pz;
        p.hasRot[i] = true;
        p.hasPos[i] = true;
    }

    /** F3+T: перечитати wounded.json без перезапуску гри. */
    @Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Reload {
        private Reload() {}

        @SubscribeEvent
        public static void onRegister(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) rm -> cached = null);
        }
    }

    // ── Коли поза діє ────────────────────────────────────────────────────

    /** Чи це кадр першої особи локального гравця (модель там малює лише руку). */
    private static boolean isFirstPersonSelf(Player player) {
        Minecraft mc = Minecraft.getInstance();
        return player == mc.player && mc.options.getCameraType().isFirstPerson();
    }

    private static boolean poseAllowed(Player player) {
        if (player.isPassenger() || player.isDeadOrDying() || player.isSleeping()) return false;
        Pose current = player.getPose();
        return current == Pose.STANDING || current == Pose.CROUCHING;
    }

    // ── Застосування ─────────────────────────────────────────────────────

    /**
     * Пише wounded у модель цього гравця. Викликається з {@code setupAnim TAIL}
     * для КОЖНОГО гравця (модель спільна) — тому для не-поранених повертає
     * {@code false} і нічого не чіпає.
     *
     * @return {@code true}, якщо щось записано в {@code head/body.x/z}
     *         (міксин тоді запам'ятає, що їх треба буде повернути)
     */
    public static boolean applyToModel(PlayerModel<?> m, Player player) {
        UUID id = player.getUUID();

        boolean active = ClientMatchState.survivorStateOf(id) == SurvivorState.BROKEN_LEG
            && poseAllowed(player)
            && !WakeUpAnimation.isPlaying();

        // Хто зараз веде руку: тоді wounded для неї відступає (з плавним поверненням).
        boolean rightBusy = false, leftBusy = false;
        if (active) {
            boolean rightMain = player.getMainArm() == HumanoidArm.RIGHT;

            // Ліхтар у руці — його піднімає MixinFlashlightHoldUpThirdPerson.
            boolean mainFlash = FlashlightItem.isFlashlight(player.getMainHandItem());
            boolean offFlash = FlashlightItem.isFlashlight(player.getOffhandItem());
            rightBusy |= rightMain ? mainFlash : offFlash;
            leftBusy  |= rightMain ? offFlash : mainFlash;

            // Використання предмета (аптечка, шина, ...) — ванільна поза предмета.
            if (player.isUsingItem()) {
                boolean usedRight = (player.getUsedItemHand() == InteractionHand.MAIN_HAND) == rightMain;
                if (usedRight) rightBusy = true; else leftBusy = true;
            }

            // Підбір предмета з землі — права рука (MixinGeneratorWorkThirdPerson).
            if (ItemPickupPose.elapsedSeconds(id, 0f) >= 0f) rightBusy = true;

            // Ремонт — обидві руки, заправка — права.
            int action = ClientMatchState.generatorInteractionOf(id);
            if (action == ClientMatchState.GENERATOR_ACTION_REPAIR) { rightBusy = true; leftBusy = true; }
            else if (action == ClientMatchState.GENERATOR_ACTION_REFUEL) rightBusy = true;
        }

        Blend b = advance(id, active, active && !rightBusy, active && !leftBusy);
        if (b == null) return false;
        if (isFirstPersonSelf(player)) return false; // вагу веди, модель не чіпай

        Target p = target();
        boolean crouching = player.getPose() == Pose.CROUCHING;
        boolean wrote = false;

        // Руки: абсолютний кут (це й прибирає гойдання ходьби), позиція — додається.
        if (b.right > 0f) blendArm(m.rightArm, p, R_ARM, ease(b.right));
        if (b.left > 0f)  blendArm(m.leftArm,  p, L_ARM, ease(b.left));

        // Голова і торс: нахил додається до ванільного, x/z — абсолютні.
        if (b.body > 0f) {
            float e = ease(b.body);
            addLean(m.head, p, HEAD, e);
            m.head.xRot = Math.min(m.head.xRot, MAX_HEAD_PITCH);
            if (!crouching) addLean(m.body, p, BODY, e);
            else { m.body.x = 0f; m.body.z = 0f; }   // нахил торса дає присідання
            wrote = true;
        }

        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        return wrote;
    }

    private static void blendArm(ModelPart arm, Target p, int i, float e) {
        if (p.hasRot[i]) {
            arm.xRot = Mth.lerp(e, arm.xRot, HandPoseTracks.rad(p.rot[i][0]));
            arm.yRot = Mth.lerp(e, arm.yRot, HandPoseTracks.rad(p.rot[i][1]));
            arm.zRot = Mth.lerp(e, arm.zRot, HandPoseTracks.rad(p.rot[i][2]));
        }
        if (p.hasPos[i]) {
            arm.x += p.pos[i][0] * e;
            arm.y += p.pos[i][1] * e;
            arm.z += p.pos[i][2] * e;
        }
    }

    private static void addLean(ModelPart part, Target p, int i, float e) {
        if (p.hasRot[i]) {
            part.xRot += HandPoseTracks.rad(p.rot[i][0]) * e;
            part.yRot += HandPoseTracks.rad(p.rot[i][1]) * e;
            part.zRot += HandPoseTracks.rad(p.rot[i][2]) * e;
        }
        if (p.hasPos[i]) {
            part.x = p.pos[i][0] * e;      // абсолютні: див. restorePersistentBase
            part.y += p.pos[i][1] * e;     // y ванільний код скидає щокадру
            part.z = p.pos[i][2] * e;
        }
    }

    /**
     * Повертає в норму те, що ванільний {@code setupAnim} сам не скидає
     * ({@code head/body.x/z}). Міксин кличе це один раз, коли на цьому
     * екземплярі моделі поранений гравець змінюється на непораненого.
     */
    public static void restorePersistentBase(PlayerModel<?> m) {
        m.head.x = 0f; m.head.z = 0f;
        m.body.x = 0f; m.body.z = 0f;
    }

    // ── Ваги ─────────────────────────────────────────────────────────────

    private static float ease(float w) {
        return w * w * (3f - 2f * w);
    }

    /**
     * Рухає ваги до цілей за реальним часом. {@code null}, коли поза вже
     * повністю зійшла і гравець не поранений (запис видаляється).
     */
    private static Blend advance(UUID id, boolean body, boolean right, boolean left) {
        Blend b = BLENDS.get(id);
        long now = System.nanoTime();
        if (b == null) {
            if (!body) return null;
            b = new Blend();
            b.lastNanos = now;
            BLENDS.put(id, b);
        }
        float dt = Math.min((now - b.lastNanos) / 1_000_000_000f, 0.1f);
        b.lastNanos = now;
        float step = dt / BLEND_SECONDS;

        b.body  = approach(b.body,  body  ? 1f : 0f, step);
        b.right = approach(b.right, right ? 1f : 0f, step);
        b.left  = approach(b.left,  left  ? 1f : 0f, step);

        if (!body && b.body <= 0f && b.right <= 0f && b.left <= 0f) {
            BLENDS.remove(id);
            return null;
        }
        return b;
    }

    private static float approach(float cur, float target, float step) {
        return cur < target ? Math.min(target, cur + step) : Math.max(target, cur - step);
    }

    /** Скидання між матчами (див. {@code ClientMatchState.reset}). */
    public static void reset() {
        BLENDS.clear();
    }
}
