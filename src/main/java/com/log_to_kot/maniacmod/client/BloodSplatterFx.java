package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * Кров від удару маньяка: бризки в повітрі, плями на підлозі й стінах,
 * патьоки по стінах і калюжа під тим, кого збили з ніг.
 *
 * ── Як це працює ─────────────────────────────────────────────────────
 *   1. Прийшов {@code BloodSplatterPacket} — {@link #spawn}. Для кожної краплі
 *      ми ОДРАЗУ, одним прорахунком, знаходимо, куди вона впаде: летимо по
 *      параболі (гравітація) маленькими кроками й на кожному кроці питаємо
 *      світ {@code level.clip(...)}, чи не вдарилась вона в блок. Перший
 *      блок на шляху — і є місце плями, а грань блока — її площина
 *      (підлога, стіна чи стеля).
 *   2. Далі лишається лише ПОКАЗУВАТИ: поки минув час польоту — малюємо
 *      маленьку краплю на її траєкторії; щойно «долетіла» — пляма з'являється
 *      й швидко розтікається.
 *   3. Плями лишаються до кінця матчу ({@link #clear} з {@code ClientMatchState.reset}),
 *      повільно «засихають» (світло-червоні → темно-бурі). Ліміт
 *      {@link #MAX_DROPS}: коли перебір — зникають найстаріші.
 *
 * ── Чому однакове в усіх ─────────────────────────────────────────────
 * Уся випадковість береться з {@code seed} із пакета, тож кожен клієнт
 * малює ті самі бризки в тих самих місцях, а мережею їде кілька байтів.
 *
 * ── Текстури ─────────────────────────────────────────────────────────
 * Справжні піксель-арт спрайти з {@code textures/particle/blood/}: {@code puddle1…8} (плями й
 * калюжі), {@code drop1/2} (розсип дрібних крапель, також кадри вибуху в мить удару). Сірі спрайти фарбуються в колір
 * крові множенням. Малюється через {@code RenderType.text(текстура)} — він бере справжнє світло
 * світу, тож у темряві кров не світиться. Старий згенерований атлас прибрано.
 *
 * ── Пляма не висить у повітрі (БАГФІКС) ──────────────────────────────
 * Пляма — квадрат фіксованого розміру, а грань блока може бути меншою: на краю блока (з одного
 * боку твердо, з іншого повітря) частина квадрата висіла б у порожнечі. Тепер перед малюванням
 * квадрат ОБРІЗАЄТЬСЯ по клітинках блоків у площині плями: малюється лише там, де під ним є
 * повна тверда грань, відкрита назовні. Кров не заходить ні за край блока, ні крізь стіну
 * (куток підлога/стіна обрізається по стіні). Нестандартна форма блока (плита, сходи, паркан):
 * пляма лишається лише в межах того блока, в який влучила.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BloodSplatterFx {

    /** Скільки крапель/плям тримаємо одночасно; понад це зникають найстаріші. */
    private static final int MAX_DROPS = 450;

    /** Гравітація краплі, блоків/с² (ванільні сутності ≈ 32). */
    private static final double GRAVITY = 28.0;
    /** Крок прорахунку траєкторії, с. */
    private static final double STEP = 1.0 / 30.0;
    /** Скільки секунд максимум летить крапля, поки не впаде. */
    private static final double MAX_FLIGHT = 1.4;

    /** Далі цієї відстані (блоків) плями не малюємо. */
    private static final double RENDER_DISTANCE = 64.0;

    // ── Текстури (textures/particle/blood/*.png, усі 16×16) ──
    private static ResourceLocation tex(String name) {
        return new ResourceLocation(ManiacMod.MOD_ID, "textures/particle/blood/" + name + ".png");
    }
    /** Великі шипуваті плями. */
    private static final ResourceLocation[] SPLAT_BIG =
        { tex("puddle1"), tex("puddle2"), tex("puddle3"), tex("puddle5") };
    /** Витягнуті плями (удар під кутом): їх повертаємо вздовж руху краплі. */
    private static final ResourceLocation[] SPLAT_STREAK = { tex("puddle6"), tex("puddle7") };
    /** Розсипи дрібних крапель. */
    private static final ResourceLocation[] SPLAT_SPATTER =
        { tex("drop1"), tex("drop2"), tex("puddle4"), tex("puddle8") };
    /** Калюжа під жертвою. */
    private static final ResourceLocation[] POOL = { tex("puddle1"), tex("puddle2"), tex("puddle3") };
    /** Кадри вибуху бризок у мить удару. */
    private static final ResourceLocation[] BURST = { tex("drop1"), tex("drop2") };
    /** Смужка 1×5 (темний хвіст → світла голова): шлейф краплі й патьок. */

    private static final float BURST_SECONDS = 0.38f;
    private static final int FULL_BRIGHT = 0xF000F0;

    /** Відступ плями від поверхні, щоб не мерехтіла в одній площині з блоком… */
    private static final double LIFT_BASE = 0.006;
    /** …і його приріст на кожен блок відстані (буфер глибини з відстанню грубшає). */
    private static final double LIFT_PER_BLOCK = 0.0005;

    /** За скільки секунд свіжа кров підсихає до темної. */
    private static final float DRY_SECONDS = 90f;

    /** Радіус маски клітинок навколо блока влучання (5×5): пляма не більша за ~1,4 блока. */
    private static final int MASK_R = 2;
    private static final int MASK_W = MASK_R * 2 + 1;

    private static final ArrayDeque<Drop> drops = new ArrayDeque<>();
    private static final ArrayDeque<Burst> bursts = new ArrayDeque<>();
    /** Світ, у якому лежать плями: змінився (інший вимір, новий вхід) — стираємо. */
    private static ClientLevel levelOfDrops = null;

    private BloodSplatterFx() {}

    /** Одна крапля: політ + пляма на місці приземлення (або лише політ, якщо не влучила). */
    private static final class Drop {
        // політ
        double sx, sy, sz;
        double vx, vy, vz;
        double born;
        double flight;
        boolean showFlying = true;
        float dropSize = 0.03f;

        // пляма
        boolean lands;
        double px, py, pz;
        int nx, ny, nz;
        float ax, ay, az;
        float bx, by, bz;
        float radius;
        float elong;
        float growSeconds = 0.12f;
        ResourceLocation tex;  // спрайт плями
        float halfA, halfB;    // половина розміру плями по осях a і b, блоків
        int layer;

        // грань, у яку влучили, і маска «де на ній справді є тверда поверхня»
        BlockPos hitPos;
        Direction face;
        boolean fullFace;
        boolean maskReady;
        int mask;              // біт (i1+R)*W+(i2+R): клітинка валідна

        // патьік (лише на стінах)
        float dripLen;
        float dripWidth;
        float dripOffset;

        // кеш світла
        int light = FULL_BRIGHT;
        double lightAt = -1e9;
    }

    /** Анімований вибух бризок у мить удару (обернений до камери). */
    private static final class Burst {
        double x, y, z;
        double born;
        float half;
        int light = FULL_BRIGHT;
    }

    // ── Запуск ───────────────────────────────────────────────────────

    public static void spawn(double x, double y, double z, float awayX, float awayZ,
                             boolean downed, int count, long seed) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player probe = mc.player;
        if (level == null || probe == null) return;
        if (level != levelOfDrops) {
            drops.clear();
            bursts.clear();
            levelOfDrops = level;
        }

        Random rnd = new Random(seed);
        double now = nowSeconds();
        Vec3 origin = new Vec3(x, y, z);
        spawnLight = LevelRenderer.getLightColor(level, BlockPos.containing(origin));

        boolean hasDir = awayX * awayX + awayZ * awayZ > 1.0e-6f;
        double baseYaw = hasDir ? Math.atan2(awayZ, awayX) : rnd.nextDouble() * Math.PI * 2.0;

        int total = Math.max(1, downed ? Math.round(count * 1.5f) : count);
        for (int i = 0; i < total; i++) {
            // Більшість крапель летить «крізь» жертву від маньяка, частина — назад,
            // на маньяка: так кров лишається і з його боку.
            boolean back = rnd.nextFloat() < 0.2f;
            double yaw = baseYaw + (back ? Math.PI : 0.0)
                + Mth.clamp(rnd.nextGaussian() * 0.5, -1.3, 1.3);
            double speed = back ? 1.5 + rnd.nextDouble() * 4.0 : 2.5 + rnd.nextDouble() * 8.5;
            double lift = -0.2 + rnd.nextDouble() * 0.8;

            double vx = Math.cos(yaw) * speed;
            double vz = Math.sin(yaw) * speed;
            double vy = speed * lift + 1.2;

            // Здебільшого дрібні плямки, зрідка велика.
            float size = 0.045f + (float) (rnd.nextDouble() * rnd.nextDouble()) * 0.17f;
            if (rnd.nextFloat() < 0.1f) size += 0.08f + rnd.nextFloat() * 0.1f;
            if (downed) size *= 1.15f;

            double born = now + rnd.nextDouble() * 0.04;
            launch(level, probe, rnd, origin, vx, vy, vz, born, size);
        }

        // Калюжа на підлозі під жертвою: маленька за звичайного удару,
        // велика й повільна, якщо збили з ніг.
        BlockHitResult floor = level.clip(new ClipContext(origin, origin.add(0.0, -4.0, 0.0),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, probe));
        if (floor.getType() != HitResult.Type.MISS && floor.getDirection() == Direction.UP) {
            float poolRadius = downed ? 0.45f + rnd.nextFloat() * 0.25f : 0.2f + rnd.nextFloat() * 0.1f;
            float poolGrow = downed ? 5.0f : 1.6f;
            addPool(level, rnd, floor, now + 0.35, poolRadius, poolGrow);
        }

        // Анімований вибух бризок у мить удару — миттєва реакція, поки краплі ще летять.
        Burst burst = new Burst();
        burst.x = x; burst.y = y; burst.z = z;
        burst.born = now;
        burst.half = downed ? 0.85f : 0.6f;
        burst.light = spawnLight;
        if (bursts.size() >= 12) bursts.pollFirst();
        bursts.addLast(burst);
    }

    /** Світло в точці удару: літаючі краплі й вибух беруть його, щоб не світитись у темряві. */
    private static int spawnLight = FULL_BRIGHT;

    /**
     * Прораховує політ ОДНІЄЇ краплі до першого блока й кладе її в чергу.
     * Не влучила за {@link #MAX_FLIGHT} — просто пролітає й зникає без плями.
     */
    private static void launch(ClientLevel level, Player probe, Random rnd, Vec3 o,
                               double vx, double vy, double vz, double born, float size) {
        Vec3 prev = o;
        for (double t = STEP; t <= MAX_FLIGHT + 1.0e-6; t += STEP) {
            Vec3 next = new Vec3(
                o.x + vx * t,
                o.y + vy * t - 0.5 * GRAVITY * t * t,
                o.z + vz * t);
            BlockHitResult hit = level.clip(new ClipContext(prev, next,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, probe));
            if (hit.getType() != HitResult.Type.MISS) {
                double seg = prev.distanceTo(next);
                double frac = seg > 1.0e-9 ? prev.distanceTo(hit.getLocation()) / seg : 1.0;
                double flight = (t - STEP) + STEP * frac;
                addLanded(level, rnd, o, vx, vy, vz, born, flight, hit, size);
                return;
            }
            prev = next;
        }
        Drop d = new Drop();
        d.sx = o.x; d.sy = o.y; d.sz = o.z;
        d.vx = vx; d.vy = vy; d.vz = vz;
        d.born = born;
        d.flight = MAX_FLIGHT;
        d.dropSize = size;
        d.light = spawnLight;
        d.lands = false;
        add(d);
    }

    private static void addLanded(ClientLevel level, Random rnd, Vec3 o, double vx, double vy, double vz,
                                  double born, double flight, BlockHitResult hit, float size) {
        Direction face = hit.getDirection();
        Vec3 p = hit.getLocation();

        Drop d = new Drop();
        d.sx = o.x; d.sy = o.y; d.sz = o.z;
        d.vx = vx; d.vy = vy; d.vz = vz;
        d.born = born;
        d.flight = flight;
        d.dropSize = size;
        d.light = spawnLight;
        d.lands = true;
        d.px = p.x; d.py = p.y; d.pz = p.z;
        d.nx = face.getStepX(); d.ny = face.getStepY(); d.nz = face.getStepZ();
        d.hitPos = hit.getBlockPos();
        d.face = face;
        d.fullFace = level.getBlockState(d.hitPos).isFaceSturdy(level, d.hitPos, face);
        d.radius = size;
        d.growSeconds = 0.12f;
        d.layer = rnd.nextInt(8);

        // Швидкість у момент удару: від неї залежить, наскільки пляма витягнута.
        double ivx = vx, ivy = vy - GRAVITY * flight, ivz = vz;
        double dot = ivx * d.nx + ivy * d.ny + ivz * d.nz;
        double tx = ivx - d.nx * dot, ty = ivy - d.ny * dot, tz = ivz - d.nz * dot;
        double tLen = Math.sqrt(tx * tx + ty * ty + tz * tz);
        double nLen = Math.abs(dot);
        d.elong = (float) Mth.clamp(tLen / (nLen + 1.0) * 0.35, 0.0, 1.2);
        setBasis(d, tx, ty, tz, tLen, rnd);

        chooseSprite(d, rnd, size);

        // На стіні велика крапля починає стікати вниз.
        if (d.ny == 0 && size > 0.07f) {
            d.dripLen = (0.15f + rnd.nextFloat() * 0.55f) * Mth.clamp(size / 0.12f, 0.6f, 1.6f);
            d.dripWidth = 0.02f + rnd.nextFloat() * 0.02f;
            d.dripOffset = (rnd.nextFloat() - 0.5f) * size * 0.8f;
        }
        add(d);
    }

    private static void addPool(ClientLevel level, Random rnd, BlockHitResult floor, double born,
                                float radius, float grow) {
        Vec3 p = floor.getLocation();
        Drop d = new Drop();
        d.born = born;
        d.flight = 0.0;
        d.showFlying = false;
        d.lands = true;
        d.px = p.x; d.py = p.y; d.pz = p.z;
        d.nx = 0; d.ny = 1; d.nz = 0;
        d.hitPos = floor.getBlockPos();
        d.face = Direction.UP;
        d.fullFace = level.getBlockState(d.hitPos).isFaceSturdy(level, d.hitPos, Direction.UP);
        d.radius = radius;
        d.elong = 0f;
        d.growSeconds = grow;
        d.layer = 0;
        setBasis(d, 0, 0, 0, 0, rnd);
        rotateBasis(d, rnd.nextFloat() * Mth.TWO_PI);
        d.tex = POOL[rnd.nextInt(POOL.length)];
        d.halfA = d.halfB = radius * 1.15f;
        d.light = spawnLight;
        add(d);
    }

    /**
     * Яка текстура й якого розміру: дрібне — розсип крапель; удар під кутом —
     * витягнута пляма (вісь {@code a} уздовж руху краплі); решта —
     * шипувата пляма, випадково повернута в площині.
     */
    private static void chooseSprite(Drop d, Random rnd, float size) {
        d.light = spawnLight;
        if (size < 0.075f) {
            d.tex = SPLAT_SPATTER[rnd.nextInt(SPLAT_SPATTER.length)];
            d.halfA = d.halfB = 0.09f + size * 1.3f;
            rotateBasis(d, rnd.nextFloat() * Mth.TWO_PI);
        } else if (d.elong > 0.45f) {
            d.tex = SPLAT_STREAK[rnd.nextInt(SPLAT_STREAK.length)];
            d.halfA = size * 1.9f * (1f + d.elong * 0.35f) + 0.04f;
            d.halfB = size * 1.5f + 0.03f;
        } else {
            d.tex = SPLAT_BIG[rnd.nextInt(SPLAT_BIG.length)];
            d.halfA = d.halfB = size * 1.5f + 0.04f;
            rotateBasis(d, rnd.nextFloat() * Mth.TWO_PI);
        }
    }

    /** Повертає осі a/b у площині грані на кут (щоб однакові спрайти не повторювались). */
    private static void rotateBasis(Drop d, float angle) {
        float c = Mth.cos(angle), s = Mth.sin(angle);
        float nax = d.ax * c + d.bx * s, nay = d.ay * c + d.by * s, naz = d.az * c + d.bz * s;
        float nbx = d.bx * c - d.ax * s, nby = d.by * c - d.ay * s, nbz = d.bz * c - d.az * s;
        d.ax = nax; d.ay = nay; d.az = naz;
        d.bx = nbx; d.by = nby; d.bz = nbz;
    }

    /**
     * Дві осі в площині грані: {@code a} — уздовж руху краплі (куди пляма
     * витягнута), {@code b} — впоперек. Якщо руху вздовж площини нема
     * (падає прямо) — напрям випадковий.
     */
    private static void setBasis(Drop d, double tx, double ty, double tz, double tLen, Random rnd) {
        float ax, ay, az;
        if (tLen > 1.0e-3) {
            ax = (float) (tx / tLen); ay = (float) (ty / tLen); az = (float) (tz / tLen);
        } else {
            float e1x, e1y, e1z, e2x, e2y, e2z;
            if (d.ny != 0)      { e1x = 1; e1y = 0; e1z = 0; e2x = 0; e2y = 0; e2z = 1; }
            else if (d.nx != 0) { e1x = 0; e1y = 1; e1z = 0; e2x = 0; e2y = 0; e2z = 1; }
            else                { e1x = 1; e1y = 0; e1z = 0; e2x = 0; e2y = 1; e2z = 0; }
            double phi = rnd.nextDouble() * Math.PI * 2.0;
            float c = (float) Math.cos(phi), s = (float) Math.sin(phi);
            ax = e1x * c + e2x * s; ay = e1y * c + e2y * s; az = e1z * c + e2z * s;
        }
        d.ax = ax; d.ay = ay; d.az = az;
        // b = n × a
        d.bx = d.ny * az - d.nz * ay;
        d.by = d.nz * ax - d.nx * az;
        d.bz = d.nx * ay - d.ny * ax;
    }

    private static void add(Drop d) {
        if (drops.size() >= MAX_DROPS) drops.pollFirst();
        drops.addLast(d);
    }

    public static void clear() {
        drops.clear();
        bursts.clear();
        levelOfDrops = null;
    }

    // ── Рендер ───────────────────────────────────────────────────────

    /** Буфери кадру: по одному на текстуру (RenderType.text кешується ваніллю), закриваємо в кінці. */
    private static final class Batches {
        final MultiBufferSource.BufferSource buffers;
        final Map<ResourceLocation, VertexConsumer> open = new HashMap<>();
        final Map<ResourceLocation, RenderType> types = new HashMap<>();

        Batches(MultiBufferSource.BufferSource buffers) { this.buffers = buffers; }

        VertexConsumer of(ResourceLocation tex) {
            VertexConsumer vc = open.get(tex);
            if (vc == null) {
                RenderType type = RenderType.text(tex);
                types.put(tex, type);
                vc = buffers.getBuffer(type);
                open.put(tex, vc);
            }
            return vc;
        }

        void endAll() {
            for (RenderType type : types.values()) buffers.endBatch(type);
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (drops.isEmpty() && bursts.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level != levelOfDrops) {
            clear();
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        Matrix4f m = event.getPoseStack().last().pose();
        Vector3f left = event.getCamera().getLeftVector();
        Vector3f up = event.getCamera().getUpVector();
        Batches batches = new Batches(mc.renderBuffers().bufferSource());

        double now = nowSeconds();
        double maxDistSq = RENDER_DISTANCE * RENDER_DISTANCE;

        Iterator<Drop> it = drops.iterator();
        while (it.hasNext()) {
            Drop d = it.next();
            double t = now - d.born;
            if (t < 0.0) continue;

            if (t < d.flight) continue;
            if (!d.lands) {
                it.remove();
                continue;
            }

            double dx = d.px - cam.x, dy = d.py - cam.y, dz = d.pz - cam.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > maxDistSq) continue;

            drawSplat(batches, m, cam, level, d, now, now - d.born - d.flight, Math.sqrt(distSq));
        }

        Iterator<Burst> bit = bursts.iterator();
        while (bit.hasNext()) {
            Burst b = bit.next();
            double age = now - b.born;
            if (age > BURST_SECONDS) {
                bit.remove();
                continue;
            }
            drawBurst(batches, m, cam, left, up, b, age);
        }
        batches.endAll();
    }

    /** Вибух бризок: розсип крапель розлітається й тане; спрайт повернутий до камери. */
    private static void drawBurst(Batches batches, Matrix4f m, Vec3 cam,
                                  Vector3f left, Vector3f up, Burst b, double age) {
        float k = Mth.clamp((float) (age / BURST_SECONDS), 0f, 1f);
        // Швидко росте, під кінець гасне.
        float h = b.half * (0.35f + 0.65f * (1f - (1f - k) * (1f - k)));
        int alpha = Math.round(255f * (1f - k * k));
        VertexConsumer vc = batches.of(BURST[k < 0.5f ? 0 : 1]);

        double x = b.x - cam.x, y = b.y - cam.y, z = b.z - cam.z;
        float lx = left.x() * h, ly = left.y() * h, lz = left.z() * h;
        float ux = up.x() * h, uy = up.y() * h, uz = up.z() * h;
        int r = 165, g = 0, bl = 20;

        quadVertex(vc, m, x + lx + ux, y + ly + uy, z + lz + uz, r, g, bl, alpha, 0f, 0f, b.light);
        quadVertex(vc, m, x - lx + ux, y - ly + uy, z - lz + uz, r, g, bl, alpha, 1f, 0f, b.light);
        quadVertex(vc, m, x - lx - ux, y - ly - uy, z - lz - uz, r, g, bl, alpha, 1f, 1f, b.light);
        quadVertex(vc, m, x + lx - ux, y + ly - uy, z + lz - uz, r, g, bl, alpha, 0f, 1f, b.light);
    }

    // ── Пляма на поверхні (з обрізанням по реальних гранях) ───────────

    private static int axisOf(Direction face) {
        return switch (face.getAxis()) { case X -> 0; case Y -> 1; case Z -> 2; };
    }

    /** Дві осі площини плями (0=x, 1=y, 2=z) — ті, що не збігаються з нормаллю. */
    private static int axis1(int axisN) { return axisN == 0 ? 1 : 0; }
    private static int axis2(int axisN) { return axisN == 2 ? 1 : 2; }

    private static double comp(double x, double y, double z, int axis) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    private static int blockComp(BlockPos p, int axis) {
        return axis == 0 ? p.getX() : axis == 1 ? p.getY() : p.getZ();
    }

    /**
     * Чи є клітинка зі зсувом {@code (i1, i2)} від блока влучання справжньою відкритою твердою
     * гранню в тій самій площині. Свій блок — завжди так (в нього влучили). Сусідні — лише якщо
     * грань, у яку влучили, повна, а сусід має повну тверду грань і перед нею НЕ стоїть суцільний
     * блок (інакше це стіна, крізь яку кров не йде).
     */
    private static boolean cellValid(ClientLevel level, Drop d, int a1, int a2, int i1, int i2) {
        if (i1 == 0 && i2 == 0) return true;
        if (!d.fullFace) return false;
        int[] off = new int[3];
        off[a1] = i1;
        off[a2] = i2;
        BlockPos back = d.hitPos.offset(off[0], off[1], off[2]);
        if (!level.isLoaded(back)) return false;
        BlockState state = level.getBlockState(back);
        if (!state.isFaceSturdy(level, back, d.face)) return false;
        BlockPos front = back.relative(d.face);
        return !level.getBlockState(front).isCollisionShapeFullBlock(level, front);
    }

    private static void ensureMask(ClientLevel level, Drop d) {
        if (d.maskReady) return;
        int axisN = axisOf(d.face);
        int a1 = axis1(axisN), a2 = axis2(axisN);
        int mask = 0;
        for (int i1 = -MASK_R; i1 <= MASK_R; i1++) {
            for (int i2 = -MASK_R; i2 <= MASK_R; i2++) {
                if (cellValid(level, d, a1, a2, i1, i2)) mask |= 1 << ((i1 + MASK_R) * MASK_W + (i2 + MASK_R));
            }
        }
        d.mask = mask;
        d.maskReady = true;
    }

    private static boolean maskCell(Drop d, int i1, int i2) {
        if (i1 < -MASK_R || i1 > MASK_R || i2 < -MASK_R || i2 > MASK_R) return false;
        return (d.mask & (1 << ((i1 + MASK_R) * MASK_W + (i2 + MASK_R)))) != 0;
    }

    /** Пляма на поверхні: розтікається за {@code growSeconds}, потім підсихає. */
    private static void drawSplat(Batches batches, Matrix4f m, Vec3 cam, ClientLevel level,
                                  Drop d, double now, double age, double dist) {
        // Розтікання з швидким початком і плавним кінцем.
        float k = Mth.clamp((float) (age / d.growSeconds), 0f, 1f);
        float grow = 1f - (1f - k) * (1f - k) * (1f - k);
        float scale = 0.3f + 0.7f * grow;

        // Світло з блока біля плями (раз на секунду): у темряві кров не світиться.
        if (now - d.lightAt > 1.0) {
            BlockPos lp = BlockPos.containing(d.px + d.nx * 0.5, d.py + d.ny * 0.5, d.pz + d.nz * 0.5);
            d.light = LevelRenderer.getLightColor(level, lp);
            d.lightAt = now;
        }
        ensureMask(level, d);

        // Свіжа кров яскравіша й червоніша, висохла — темна. Текстура сіра, тож це множник.
        float dry = Mth.clamp((float) (age / DRY_SECONDS), 0f, 1f);
        int r = Math.round(Mth.lerp(dry, 160f, 84f));
        int g = 0;
        int b = Math.round(Mth.lerp(dry, 18f, 11f));
        int a = 255;

        double lift = LIFT_BASE + dist * LIFT_PER_BLOCK + d.layer * 0.0003 * (1.0 + dist * 0.05);
        double cx = d.px + d.nx * lift, cy = d.py + d.ny * lift, cz = d.pz + d.nz * lift;

        float ha = d.halfA * scale, hb = d.halfB * scale;
        float aX = d.ax * ha, aY = d.ay * ha, aZ = d.az * ha;
        float bX = d.bx * hb, bY = d.by * hb, bZ = d.bz * hb;

        int axisN = axisOf(d.face);
        int a1 = axis1(axisN), a2 = axis2(axisN);
        double plane = comp(cx, cy, cz, axisN);

        // Чотири кути квадрата: (c1, c2) у площині + UV. Порядок і оберти — як у ванільного quad.
        double[] poly = new double[4 * 4];
        int[][] sg = { {-1, -1, 0, 0}, {1, -1, 1, 0}, {1, 1, 1, 1}, {-1, 1, 0, 1} };
        double min1 = Double.MAX_VALUE, max1 = -Double.MAX_VALUE, min2 = Double.MAX_VALUE, max2 = -Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            double wx = cx + sg[i][0] * aX + sg[i][1] * bX;
            double wy = cy + sg[i][0] * aY + sg[i][1] * bY;
            double wz = cz + sg[i][0] * aZ + sg[i][1] * bZ;
            double c1 = comp(wx, wy, wz, a1), c2 = comp(wx, wy, wz, a2);
            poly[i * 4] = c1;
            poly[i * 4 + 1] = c2;
            poly[i * 4 + 2] = sg[i][2] == 0 ? 0.0001 : 0.9999;
            poly[i * 4 + 3] = sg[i][3] == 0 ? 0.0001 : 0.9999;
            min1 = Math.min(min1, c1); max1 = Math.max(max1, c1);
            min2 = Math.min(min2, c2); max2 = Math.max(max2, c2);
        }

        VertexConsumer vc = batches.of(d.tex);
        int base1 = blockComp(d.hitPos, a1), base2 = blockComp(d.hitPos, a2);
        int lo1 = (int) Math.floor(min1) - base1, hi1 = (int) Math.floor(max1) - base1;
        int lo2 = (int) Math.floor(min2) - base2, hi2 = (int) Math.floor(max2) - base2;

        boolean allValid = true;
        for (int i1 = lo1; i1 <= hi1 && allValid; i1++) {
            for (int i2 = lo2; i2 <= hi2; i2++) {
                if (!maskCell(d, i1, i2)) { allValid = false; break; }
            }
        }

        if (allValid) {
            emitPoly(vc, m, cam, axisN, a1, a2, plane, poly, 4, r, g, b, a, d.light);
        } else {
            double[] tmpA = new double[16 * 4], tmpB = new double[16 * 4];
            for (int i1 = Math.max(lo1, -MASK_R); i1 <= Math.min(hi1, MASK_R); i1++) {
                for (int i2 = Math.max(lo2, -MASK_R); i2 <= Math.min(hi2, MASK_R); i2++) {
                    if (!maskCell(d, i1, i2)) continue;
                    System.arraycopy(poly, 0, tmpA, 0, 16);
                    int n = 4;
                    n = clip(tmpA, n, tmpB, 0, base1 + i1, true);
                    if (n >= 3) n = clip(tmpB, n, tmpA, 0, base1 + i1 + 1, false); else continue;
                    if (n >= 3) n = clip(tmpA, n, tmpB, 1, base2 + i2, true); else continue;
                    if (n >= 3) n = clip(tmpB, n, tmpA, 1, base2 + i2 + 1, false); else continue;
                    if (n >= 3) emitPoly(vc, m, cam, axisN, a1, a2, plane, tmpA, n, r, g, b, a, d.light);
                }
            }
        }
    }

    /**
     * Обрізає опуклий багатокутник {@code src} (n вершин по 4 числа: c1, c2, u, v) пів-площиною
     * {@code c[axis] >= bound} ({@code keepGreater}) або {@code <= bound}; UV інтерполюються.
     * Повертає кількість вершин у {@code dst}.
     */
    private static int clip(double[] src, int n, double[] dst, int axis, double bound, boolean keepGreater) {
        int out = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ci = src[i * 4 + axis] - bound, cj = src[j * 4 + axis] - bound;
            if (!keepGreater) { ci = -ci; cj = -cj; }
            boolean inI = ci >= 0, inJ = cj >= 0;
            if (inI) {
                System.arraycopy(src, i * 4, dst, out * 4, 4);
                out++;
            }
            if (inI != inJ) {
                double t = ci / (ci - cj);
                for (int k = 0; k < 4; k++) {
                    dst[out * 4 + k] = src[i * 4 + k] + (src[j * 4 + k] - src[i * 4 + k]) * t;
                }
                out++;
            }
            if (out >= 15) break;
        }
        return out;
    }

    /** Малює багатокутник віялом трикутників (RenderType.text — QUADS: останню вершину дублюємо). */
    private static void emitPoly(VertexConsumer vc, Matrix4f m, Vec3 cam, int axisN, int a1, int a2,
                                 double plane, double[] poly, int n,
                                 int r, int g, int b, int a, int light) {
        for (int i = 1; i + 1 < n; i++) {
            polyVertex(vc, m, cam, axisN, a1, a2, plane, poly, 0, r, g, b, a, light);
            polyVertex(vc, m, cam, axisN, a1, a2, plane, poly, i, r, g, b, a, light);
            polyVertex(vc, m, cam, axisN, a1, a2, plane, poly, i + 1, r, g, b, a, light);
            polyVertex(vc, m, cam, axisN, a1, a2, plane, poly, i + 1, r, g, b, a, light);
        }
    }

    private static void polyVertex(VertexConsumer vc, Matrix4f m, Vec3 cam, int axisN, int a1, int a2,
                                   double plane, double[] poly, int i,
                                   int r, int g, int b, int a, int light) {
        double[] w = new double[3];
        w[axisN] = plane;
        w[a1] = poly[i * 4];
        w[a2] = poly[i * 4 + 1];
        quadVertex(vc, m, w[0] - cam.x, w[1] - cam.y, w[2] - cam.z, r, g, b, a,
            (float) poly[i * 4 + 2], (float) poly[i * 4 + 3], light);
    }

    /** Вершина формату {@code RenderType.text}: позиція, колір, UV, світло (lightmap). */
    private static void quadVertex(VertexConsumer vc, Matrix4f m, double x, double y, double z,
                                   int r, int g, int b, int a, float u, float v, int light) {
        vc.vertex(m, (float) x, (float) y, (float) z)
            .color(Mth.clamp(r, 0, 255), Mth.clamp(g, 0, 255), Mth.clamp(b, 0, 255), Mth.clamp(a, 0, 255))
            .uv(u, v)
            .uv2(light)
            .endVertex();
    }

    private static double nowSeconds() {
        return System.nanoTime() / 1.0e9;
    }
}
