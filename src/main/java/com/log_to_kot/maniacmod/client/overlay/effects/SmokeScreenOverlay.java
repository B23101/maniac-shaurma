package com.log_to_kot.maniacmod.client.overlay.effects;

import com.log_to_kot.maniacmod.client.SmokeMuffle;
import com.log_to_kot.maniacmod.client.style.CircleFx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Плями диму на екрані того, хто в хмарі димової міни.
 *
 * ── Що бачить гравець ────────────────────────────────────────────────
 * Щойно сервер сказав «ти в газі» ({@link SmokeMuffle#isActive()}), на
 * екрані одразу з'являється пачка великих м'яких сірих плям; поки гравець
 * у хмарі, ще й підсипаються нові. КОЖНА пляма живе своїм життям: швидко
 * проявляється, повільно пливе й поступово розчиняється до нуля. Вийшов із
 * хмари — нові плями не з'являються, решта доживає й зникає сама.
 *
 * ── Звідки дані ──────────────────────────────────────────────────────
 * Власного пакета немає (див. README папки): ефект читає вже наявний
 * клієнтський стан {@link SmokeMuffle}, який оновлює {@code SmokeMufflePacket}.
 * Малюється чистою геометрією {@link CircleFx#disc} (радіальний градієнт
 * альфи від центру до нуля) — без текстур, у стилі решти UI мода.
 */
@OnlyIn(Dist.CLIENT)
public final class SmokeScreenOverlay {

    private SmokeScreenOverlay() {}

    /** Одна пляма: нормалізована позиція, розмір, час життя й «клубочки» навколо центру. */
    private static final class Puff {
        float x, y;            // центр, частка ширини/висоти екрана
        float radius;          // частка меншої сторони екрана
        float driftX, driftY;  // зсув за секунду, частка екрана
        long born, life;       // мс
        int rgb;
        // два допоміжні клубочки: зсув у радіусах і розмір відносно головного
        float ox1, oy1, s1, ox2, oy2, s2;
    }

    private static final List<Puff> PUFFS = new ArrayList<>();
    private static final Random RNG = new Random();

    private static boolean wasActive = false;
    private static long nextSpawnMs = 0L;

    /** Скільки плям з'являється одразу, коли гравець увійшов у газ. */
    private static final int BURST_COUNT = 14;
    /** Пауза між новими плямами, поки гравець у газі (мс). */
    private static final long SPAWN_MIN_MS = 220L;
    private static final long SPAWN_MAX_MS = 420L;
    /** Життя плями (мс): розчиняється весь цей час. */
    private static final long LIFE_MIN_MS = 3200L;
    private static final long LIFE_MAX_MS = 5600L;
    /** Швидке проявлення на початку (мс). */
    private static final long FADE_IN_MS = 320L;
    /** Максимальна альфа центру плями (0..1). */
    private static final float PEAK_ALPHA = 0.50f;
    /** Запобіжник від розростання, якщо кадри раптом зависли. */
    private static final int MAX_PUFFS = 48;

    /** Відтінки диму: від світло-сірого до майже графітового. */
    private static final int[] SMOKE_TONES = {0xB4B9C0, 0x9CA2AA, 0x868C95, 0x6E747D, 0xC4C8CE};

    public static void render(GuiGraphics g) {
        long now = System.currentTimeMillis();
        boolean active = SmokeMuffle.isActive();

        if (active) {
            if (!wasActive) {
                for (int i = 0; i < BURST_COUNT; i++) spawn(now - RNG.nextInt(260));
                nextSpawnMs = now + SPAWN_MIN_MS;
            } else if (now >= nextSpawnMs) {
                spawn(now);
                nextSpawnMs = now + SPAWN_MIN_MS + RNG.nextInt((int) (SPAWN_MAX_MS - SPAWN_MIN_MS));
            }
        }
        wasActive = active;

        if (PUFFS.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        float unit = Math.min(w, h);

        g.pose().pushPose();
        // Над рештою HUD, під віньєткою хп (z=1900) і оверлеями вступу/ролей.
        g.pose().translate(0, 0, 1800);

        for (Iterator<Puff> it = PUFFS.iterator(); it.hasNext();) {
            Puff p = it.next();
            long age = now - p.born;
            if (age >= p.life) {
                it.remove();
                continue;
            }
            float t = age / (float) p.life;                       // 0..1 за життя
            float fadeIn = Mth.clamp(age / (float) FADE_IN_MS, 0f, 1f);
            fadeIn = fadeIn * fadeIn * (3f - 2f * fadeIn);        // smoothstep
            float fadeOut = (float) Math.pow(1f - t, 1.35);       // поступове розчинення
            float alpha = PEAK_ALPHA * fadeIn * fadeOut;
            if (alpha <= 0.01f) continue;

            float ageSec = age / 1000f;
            float cx = (p.x + p.driftX * ageSec) * w;
            float cy = (p.y + p.driftY * ageSec) * h;
            // Пляма трохи «набухає», поки розчиняється.
            float r = p.radius * unit * (1f + 0.25f * t);

            drawPuff(g, cx, cy, r, p, alpha);
        }

        g.pose().popPose();
    }

    /** Головний клубок + два менші збоку — виходить хмаринка, а не ідеальне коло. */
    private static void drawPuff(GuiGraphics g, float cx, float cy, float r, Puff p, float alpha) {
        disc(g, cx, cy, r, p.rgb, alpha);
        disc(g, cx + p.ox1 * r, cy + p.oy1 * r, r * p.s1, p.rgb, alpha * 0.85f);
        disc(g, cx + p.ox2 * r, cy + p.oy2 * r, r * p.s2, p.rgb, alpha * 0.7f);
    }

    private static void disc(GuiGraphics g, float cx, float cy, float r, int rgb, float alpha) {
        int a = Mth.clamp(Math.round(alpha * 255f), 0, 255);
        if (a <= 0 || r <= 1f) return;
        CircleFx.disc(g, cx, cy, r, (a << 24) | rgb, rgb);   // край — та сама барва з нульовою альфою
    }

    private static void spawn(long born) {
        if (PUFFS.size() >= MAX_PUFFS) return;
        Puff p = new Puff();
        // Трохи виходимо за межі екрана, щоб плями були й по краях.
        p.x = -0.05f + RNG.nextFloat() * 1.10f;
        p.y = -0.05f + RNG.nextFloat() * 1.10f;
        p.radius = 0.20f + RNG.nextFloat() * 0.24f;
        p.driftX = (RNG.nextFloat() - 0.5f) * 0.05f;
        p.driftY = (RNG.nextFloat() - 0.5f) * 0.035f - 0.006f;   // легкий дрейф угору
        p.born = born;
        p.life = LIFE_MIN_MS + RNG.nextInt((int) (LIFE_MAX_MS - LIFE_MIN_MS));
        p.rgb = SMOKE_TONES[RNG.nextInt(SMOKE_TONES.length)];
        float a1 = RNG.nextFloat() * CircleFx.TAU;
        float a2 = a1 + 2.1f + RNG.nextFloat() * 1.6f;
        p.ox1 = (float) Math.cos(a1) * 0.55f;
        p.oy1 = (float) Math.sin(a1) * 0.55f;
        p.s1 = 0.62f + RNG.nextFloat() * 0.2f;
        p.ox2 = (float) Math.cos(a2) * 0.5f;
        p.oy2 = (float) Math.sin(a2) * 0.5f;
        p.s2 = 0.5f + RNG.nextFloat() * 0.2f;
        PUFFS.add(p);
    }

    /** Кінець матчу / вихід з гри: плями зникають одразу. */
    public static void reset() {
        PUFFS.clear();
        wasActive = false;
        nextSpawnMs = 0L;
    }
}
