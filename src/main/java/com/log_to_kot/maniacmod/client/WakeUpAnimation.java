package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.anim.BoneClip;
import com.log_to_kot.maniacmod.client.anim.BoneClipLoader;
import com.log_to_kot.maniacmod.client.anim.HeadKinematics;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.io.Reader;
import java.util.Optional;
import java.util.UUID;

/**
 * Анімація пробудження: після розкидання гравців по точках спавну вони
 * лежать, потроху ворушаться і встають ({@code wake_up.json}).
 *
 * ── Що запускає ──────────────────────────────────────────────────────
 * Сервер спершу телепортує гравців ({@code applySpawnPlan}), і лише
 * потім переводить матч у {@code ROLE_REVEAL}. Клієнт вже отримує
 * {@code PhaseSyncPacket(ROLE_REVEAL)} — тому окремий пакет не потрібен:
 * старт береться з переходу фази в {@link ClientMatchState#setPhase}, а
 * годинник — від ігрового часу клієнтського світу. Усі клієнти стартують
 * від того ж пакета, тож бачать одну й ту саму секунду кліпу.
 *
 * ── Тривалість ───────────────────────────────────────────────────────
 * Дорівнює {@code animation_length} файлу, але не довша за саму фазу
 * {@code ROLE_REVEAL} ({@code roleRevealSeconds} у конфігу, за
 * замовчуванням 5 с): вихід із фази гасить анімацію. Кліп довший за фазу
 * буде обрізаний — тоді збільш {@code roleRevealSeconds}.
 *
 * ── Що вона робить ───────────────────────────────────────────────────
 *   1. Тіло — усі кістки кліпу пишуться в {@code PlayerModel} у
 *      {@code MixinWakeUpPose} (для ВСІХ глядачів).
 *   2. Камера від першої особи йде за головою з кліпу: поворот —
 *      {@link #onCameraAngles}, положення — {@code MixinCameraWakeUp}.
 *   3. Керування: камера й рух заблоковані ({@code ClientLookLock},
 *      {@code MixinKeyboardInputDownedMovement}), руки від першої
 *      особи не малюються ({@link #onRenderHand}).
 *
 * ── Немає файлу — немає анімації ────────────────────────────────────
 * Якщо {@code wake_up.json} відсутній чи зіпсований, це пишеться в лог,
 * а гра йде як раніше.
 *
 * Чисто косметика: клас нічого не вирішує в матчі.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WakeUpAnimation {

    /** {@code assets/maniacmod/player_animations/wake_up.json}. */
    public static final ResourceLocation CLIP_FILE =
        new ResourceLocation(ManiacMod.MOD_ID, "player_animations/wake_up.json");

    /** Кліп поточного запуску; {@code null} — не граємо. */
    private static BoneClip clip;
    /** Ігровий тік світу, на якому стартувала анімація. */
    private static long startTick = -1L;

    /**
     * Останній кут камери, який показала анімація (yaw, pitch). Після кінця
     * анімації цей кут записується в самого гравця, щоб камера лишилась
     * там, де зупинилась, а не відскакувала на кут, що був до анімації.
     */
    private static float lastYaw, lastPitch;
    private static boolean hasLastAngles;
    /** Анімація щойно скінчилась (сама чи вихід із фази) — кут ще не віддано гравцеві. */
    private static boolean pendingFinish;

    /** Буфери для вибірки (лише render-потік). */
    private static final float[] V = new float[3];
    private static final float[] HEAD_POS = new float[3];
    private static final float[] HEAD_ROT = new float[3];

    private WakeUpAnimation() {}

    // ── Запуск / зупинка (з ClientMatchState.setPhase) ───────────────────

    public static void onPhaseChanged(GamePhase prev, GamePhase next) {
        if (next == GamePhase.ROLE_REVEAL && prev != GamePhase.ROLE_REVEAL) {
            // Після кіно-вступу екран ще чорний: пробудження стартує в момент,
            // коли він починає світлішати (IntroClient → startDeferred()).
            if (com.log_to_kot.maniacmod.client.intro.IntroClient.isHoldingBlack()) {
                deferredStart = true;
            } else {
                start();
            }
        } else if (next != GamePhase.ROLE_REVEAL) {
            deferredStart = false;
            stop();
        }
    }

    /** Відкладений старт: чекає, доки кіно-вступ почне розтемнення. */
    private static boolean deferredStart = false;

    /** Викликає {@code IntroClient} у першу мить розтемнення. */
    public static void startDeferred() {
        if (!deferredStart) return;
        deferredStart = false;
        if (ClientMatchState.phase() == GamePhase.ROLE_REVEAL) start();
    }

    /**
     * Анімацію вставання в матчі ВИМКНЕНО (запит: гравці не лежать на старті).
     * Код і {@code wake_up.json} лишені — щоб повернути, достатньо {@code true}.
     * Поки {@code false}, {@link #isPlaying()} завжди {@code false}: камера, рух,
     * руки й модель гравця лишаються ванільними.
     */
    private static final boolean ENABLED = false;

    private static void start() {
        stop();
        if (!ENABLED) return;
        pendingFinish = false;
        hasLastAngles = false;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        BoneClip loaded = load();
        if (loaded == null) return;

        clip = loaded;
        startTick = level.getGameTime();
    }

    public static void stop() {
        // Якщо анімація йшла — кут, де вона зупинилась, треба віддати гравцеві.
        if (clip != null && hasLastAngles) pendingFinish = true;
        clip = null;
        startTick = -1L;
    }

    /** Читає кліп із ресурсів. Помилки — в лог, не в падіння. */
    private static BoneClip load() {
        Optional<net.minecraft.server.packs.resources.Resource> res =
            Minecraft.getInstance().getResourceManager().getResource(CLIP_FILE);
        if (res.isEmpty()) {
            ManiacMod.LOGGER.info("[ManiacMod] {} не знайдено — анімація пробудження пропущена", CLIP_FILE);
            return null;
        }
        try (Reader reader = res.get().openAsReader()) {
            BoneClip c = BoneClipLoader.load(reader, null,
                w -> ManiacMod.LOGGER.warn("[ManiacMod] wake_up.json: {}", w));
            ManiacMod.LOGGER.info("[ManiacMod] wake_up: кліп '{}' {} с, кістки {}",
                c.name(), c.length(), c.boneNames());
            return c;
        } catch (IOException | RuntimeException e) {
            ManiacMod.LOGGER.error("[ManiacMod] не вдалося прочитати {}: {}", CLIP_FILE, e.toString());
            return null;
        }
    }

    // ── Стан ─────────────────────────────────────────────────────────────

    /** Секунди від старту, з поправкою на partial tick. */
    private static float elapsedSeconds() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return Float.MAX_VALUE;
        return (level.getGameTime() - startTick + Minecraft.getInstance().getFrameTime()) / 20f;
    }

    /** Годинник кліпу йде (фаза ROLE_REVEAL, кліп не скінчився) — незалежно від того, чий це гравець. */
    private static boolean clockRunning() {
        if (clip == null || startTick < 0L) return false;
        if (ClientMatchState.phase() != GamePhase.ROLE_REVEAL) return false;
        return elapsedSeconds() < clip.length();
    }

    /**
     * Анімація йде просто зараз ДЛЯ ЛОКАЛЬНОГО ГРАВЦЯ (камера, руки, блок
     * керування). Маньяк анімації пробудження не програє — для нього це
     * завжди {@code false}.
     */
    public static boolean isPlaying() {
        return clockRunning() && !isManiacPlayer(localId());
    }

    /**
     * Чи малювати анімацію на моделі ЦЬОГО гравця. Маньяк «не прокидається»:
     * він стоїть одразу, тож його модель лишається ванільною для всіх глядачів.
     */
    public static boolean appliesTo(UUID playerId) {
        return !isManiacPlayer(playerId);
    }

    private static UUID localId() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? null : p.getUUID();
    }

    /**
     * Чи цей гравець — маньяк. Свою роль клієнт знає з {@code RoleSyncPacket},
     * чужу — з ростеру ({@code RosterSyncPacket}, роль кожного гравця); тому
     * роль перевіряється щокадру, а не один раз на старті: пакет ролі може
     * прийти на кадр-два пізніше за зміну фази.
     */
    private static boolean isManiacPlayer(UUID playerId) {
        if (playerId == null) return false;
        if (playerId.equals(localId()) && ClientMatchState.isManiac()) return true;
        for (RosterSyncPacket.RosterEntry entry : ClientMatchState.roster()) {
            if (entry.playerId().equals(playerId)) return entry.role() == RoleSyncPacket.Role.MANIAC;
        }
        return false;
    }

    // ── Тіло ─────────────────────────────────────────────────────────────

    /** Куди повертати позицію кістки, коли анімація скінчилась: {x, y, z} ванільної моделі. */
    private static final float[] BASE_HEAD = {0f, 0f, 0f};
    private static final float[] BASE_BODY = {0f, 0f, 0f};
    private static final float[] BASE_R_ARM = {-5f, 2f, 0f};
    private static final float[] BASE_L_ARM = {5f, 2f, 0f};
    private static final float[] BASE_R_LEG = {-1.9f, 12f, 0.1f};
    private static final float[] BASE_L_LEG = {1.9f, 12f, 0.1f};

    /**
     * Пише позу кліпу в модель. Повертає {@code false}, якщо анімація не
     * йде (тоді модель не чіпаємо).
     */
    public static boolean applyToModel(PlayerModel<?> m, UUID playerId) {
        if (!clockRunning() || !appliesTo(playerId)) return false;
        float t = elapsedSeconds();

        pose(m.head,     BoneClip.HEAD,      BASE_HEAD,  t);
        pose(m.body,     BoneClip.BODY,      BASE_BODY,  t);
        pose(m.rightArm, BoneClip.RIGHT_ARM, BASE_R_ARM, t);
        pose(m.leftArm,  BoneClip.LEFT_ARM,  BASE_L_ARM, t);
        pose(m.rightLeg, BoneClip.RIGHT_LEG, BASE_R_LEG, t);
        pose(m.leftLeg,  BoneClip.LEFT_LEG,  BASE_L_LEG, t);

        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
        return true;
    }

    /**
     * Кістка без треку лишається ванільною. Позиція — АБСОЛЮТНА
     * (база + ключ), а не {@code +=}: ванільний {@code setupAnim} не
     * скидає {@code head.x/z}, {@code body.x/z} і {@code leg.x} щокадру, а
     * модель одна на всіх гравців — накопичений зсув потрапляв би до
     * інших і росте щокадру.
     */
    private static void pose(ModelPart part, String bone, float[] base, float t) {
        if (clip.sample(bone, BoneClip.Channel.ROTATION, t, V)) {
            part.xRot = Mth.DEG_TO_RAD * V[0];
            part.yRot = Mth.DEG_TO_RAD * V[1];
            part.zRot = Mth.DEG_TO_RAD * V[2];
        }
        if (clip.sample(bone, BoneClip.Channel.POSITION, t, V)) {
            part.x = base[0] + V[0];
            part.y = base[1] + V[1];
            part.z = base[2] + V[2];
        }
    }

    /**
     * Повертає в норму те, що ванільний {@code setupAnim} сам не скидає
     * (див. {@link #pose}). Викликається міксином один раз після кожної
     * анімації на цьому екземплярі моделі.
     */
    public static void restorePersistentBase(PlayerModel<?> m) {
        m.head.x = BASE_HEAD[0];   m.head.z = BASE_HEAD[2];
        m.body.x = BASE_BODY[0];   m.body.z = BASE_BODY[2];
        m.rightLeg.x = BASE_R_LEG[0];
        m.leftLeg.x = BASE_L_LEG[0];
    }

    // ── Камера ───────────────────────────────────────────────────────────

    /** Чи кліп рухає голову — інакше камеру не чіпаємо. */
    private static boolean drivesHead() {
        return clip != null && clip.hasBone(BoneClip.HEAD);
    }

    /** Кут тіла, яким рендерер малює модель (та сама база, що й у голови в кліпі). */
    private static float bodyYaw(LivingEntity e, float partialTick) {
        return Mth.rotLerp(partialTick, e.yBodyRotO, e.yBodyRot);
    }

    /**
     * Зсув камери у СВІТОВИХ координатах для цього кадру або {@code null},
     * якщо камеру чіпати не треба. Викликається з {@code MixinCameraWakeUp}.
     */
    public static Vec3 cameraShift(Entity entity, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (entity != mc.player || !(entity instanceof LivingEntity living)) return null;
        if (!isPlaying() || !drivesHead()) return null;

        float t = elapsedSeconds();
        sampleHead(t);
        HeadKinematics.Offset o = HeadKinematics.eyeOffset(HEAD_POS, HEAD_ROT);

        // «Вперед» і «праворуч» — у системі тіла, як його малює рендерер.
        double yaw = Math.toRadians(bodyYaw(living, partialTick));
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);     // вперед
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);    // праворуч
        return new Vec3(rx * o.right() + fx * o.forward(),
                        o.up(),
                        rz * o.right() + fz * o.forward());
    }

    /** Поворот камери за кутами голови кліпу (від першої особи). */
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || event.getCamera().getEntity() != mc.player) return;

        // Анімація скінчилась: камера лишається на тому куті, де зупинилась.
        // Робимо це ДО перевірки detached — кут гравця має оновитись завжди.
        if (!isPlaying() && (pendingFinish || (clip != null && hasLastAngles))) {
            finishAndKeepAngles(mc);
            if (!event.getCamera().isDetached()) {
                event.setYaw(lastYaw);
                event.setPitch(lastPitch);
            }
            return;
        }

        if (event.getCamera().isDetached()) return;
        if (!isPlaying() || !clip.hasChannel(BoneClip.HEAD, BoneClip.Channel.ROTATION)) return;

        sampleHead(elapsedSeconds());
        // Голова в кліпі задана відносно тіла; pitch — абсолютний, як у ванільної моделі.
        float yaw = bodyYaw(mc.player, (float) event.getPartialTick()) + HEAD_ROT[1];
        float pitch = HEAD_ROT[0];
        lastYaw = yaw;
        lastPitch = pitch;
        hasLastAngles = true;
        event.setYaw(yaw);
        event.setPitch(pitch);
        event.setRoll(event.getRoll() + HEAD_ROT[2]);
    }

    /**
     * Віддає гравцеві кут, на якому зупинилась камера анімації, — щоб після
     * неї він дивився туди, куди дивився в кінці кліпу, а не туди, куди був
     * повернутий до початку.
     */
    private static void finishAndKeepAngles(Minecraft mc) {
        // Природний кінець кліпу: беремо точно кінцевий кадр, а не останній показаний.
        if (clip != null && clip.hasChannel(BoneClip.HEAD, BoneClip.Channel.ROTATION)) {
            sampleHead(clip.length());
            lastYaw = bodyYaw(mc.player, 1.0f) + HEAD_ROT[1];
            lastPitch = HEAD_ROT[0];
        }
        LocalPlayer p = mc.player;
        if (p != null && hasLastAngles) {
            float pitch = Mth.clamp(lastPitch, -90f, 90f);
            p.setYRot(lastYaw);
            p.setXRot(pitch);
            p.yRotO = lastYaw;
            p.xRotO = pitch;
            p.yHeadRot = lastYaw;
            p.yHeadRotO = lastYaw;
        }
        lastPitch = Mth.clamp(lastPitch, -90f, 90f);
        pendingFinish = false;
        hasLastAngles = false;
        clip = null;
        startTick = -1L;
    }

    /** Під час анімації руки від першої особи не малюємо — вони були б у позі лежачого. */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (isPlaying()) event.setCanceled(true);
    }

    private static void sampleHead(float t) {
        HEAD_POS[0] = HEAD_POS[1] = HEAD_POS[2] = 0f;
        HEAD_ROT[0] = HEAD_ROT[1] = HEAD_ROT[2] = 0f;
        clip.sample(BoneClip.HEAD, BoneClip.Channel.POSITION, t, HEAD_POS);
        clip.sample(BoneClip.HEAD, BoneClip.Channel.ROTATION, t, HEAD_ROT);
    }
}
