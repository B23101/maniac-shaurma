package com.log_to_kot.maniacmod.client.overlay.hotbar;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.CircleFx;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Locale;

/**
 * Круглий слот удару маньяка — правий нижній кут, з візуальною перезарядкою.
 *
 * ── Чому окремий оверлей, а не частина {@link ManiacHotbarOverlay} ───
 * Хотбар-рендерер малює лише слоти, які lib видає з
 * {@code InventorySlotAllocation}, а у маньяка їх 0, тож слот удару там
 * ніколи не з'являвся. Цей оверлей малюється незалежно від інвентаря —
 * так само, як {@link TrapPanelOverlay} для пасток.
 *
 * ── Вигляд ───────────────────────────────────────────────────────────
 * Коло лишається колом, але тепер це ГЛАДКА геометрія ({@link CircleFx}:
 * трикутні віяла й смуги з градієнтом), а не піксельні квадратики, і в
 * палітрі всього мода — темна панель, срібло, тонка рамка, рискові «тіки»,
 * блик і «дихання» як у слотах генератора та пасток.
 *
 *   • перезарядка — годинникова «завіса» стирається за стрілкою-променем із
 *     хвостом-кометою; товста дуга прогресу з яскравою голівкою; рискове
 *     кільце засвічується слідом; у центрі — секунди;
 *   • готовий — дві комети біжать по кільцю, хвиля світла біжить по рисках,
 *     м'яке срібне світіння дихає, кігті яскраві;
 *   • удар — віддача (стиск + тряска), червона ударна хвиля, три мазки
 *     кігтів, червоний відблиск, іскри;
 *   • відкат закінчився — білий спалах, подвійна хвиля-кільце, вибух іскор;
 *   • поява — пружний «поп» і докручування кільця рисок.
 *
 * Джерело даних — той самий {@code AbilityCooldownPacket.ATTACK_ID}, що
 * вже приходить від сервера при кожному ударі.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacStrikeOverlay {

    private static final float R = 30f;            // радіус диска
    private static final float TICK_IN = R + 4f;   // кільце рисок
    private static final int TICKS = 48;
    private static final int MARGIN_RIGHT = 14;
    private static final int MARGIN_BOTTOM = 14;
    private static final int ICON = 30;

    private static final float TAU = CircleFx.TAU;
    private static final float TOP = CircleFx.TOP;

    private static final int SILVER = GeneratorFuelUiTheme.FILL;          // 0xFFC2C9D6
    private static final int WHITE = 0xFFFFFFFF;
    private static final int TRACK = 0xFF292D2E;
    private static final int DANGER = GeneratorFuelUiTheme.FILL_DANGER;    // 0xFFB0242F
    private static final int DANGER_BRIGHT = 0xFFFF3B3B;

    /** Та сама іконка кігтів, що й у хотбарі (32×32, біла: тонується під стан). */
    private static final ResourceLocation TEX_STRIKE_ICON =
        new ResourceLocation(ManiacMod.MOD_ID, "textures/gui/hotbar_maniac/maniac_strike_icon.png");

    private ManiacStrikeOverlay() {}

    // ── Анімаційний стан ─────────────────────────────────────────────
    private static final long[] lastFrameNanos = new long[1];
    private static float entrance = 0f;
    private static float readyFlash = 0f;
    private static boolean wasReloading = false;
    private static float hitKick = 0f;
    private static float shockwave = 0f;
    private static float slash = 0f;
    private static float spin = 0f;
    private static float lastCooldown = 0f;
    private static final UiFx.Sparks SPARKS = new UiFx.Sparks(80);

    private static void resetAnims() {
        lastFrameNanos[0] = 0L;
        entrance = 0f;
        readyFlash = 0f;
        wasReloading = false;
        hitKick = 0f;
        shockwave = 0f;
        slash = 0f;
        spin = 0f;
        lastCooldown = 0f;
        SPARKS.clear();
    }

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.player.isSpectator()) { resetAnims(); return; }
        if (!ClientMatchState.isManiac()) { resetAnims(); return; }
        if (!ClientMatchState.allows(PhaseRule.HUD)) { resetAnims(); return; }

        long tickNow = mc.level.getGameTime();
        float cooldown = UiAnim.clamp01(ClientMatchState.abilityCooldownFraction(AbilityCooldownPacket.ATTACK_ID, tickNow));
        long ticksLeft = ClientMatchState.abilityCooldownTicksLeft(AbilityCooldownPacket.ATTACK_ID, tickNow);
        boolean reloading = cooldown > 0f;
        float progress = 1f - cooldown; // 0 — щойно вдарив, 1 — готово

        float dt = UiAnim.frameDt(lastFrameNanos);
        entrance = UiAnim.approach(entrance, 1f, 5.5f, dt);
        readyFlash = UiAnim.approach(readyFlash, 0f, 3.2f, dt);
        hitKick = UiAnim.approach(hitKick, 0f, 8f, dt);
        shockwave = UiAnim.approach(shockwave, 0f, 2.6f, dt);
        slash = UiAnim.approach(slash, 0f, 3.6f, dt);
        spin += dt * (reloading ? 0.9f : 2.1f);

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        float cx = screenW - MARGIN_RIGHT - R;
        float cy = screenH - MARGIN_BOTTOM - R;

        // Удар щойно стався (кулдаун стрибнув угору): віддача, ударна хвиля, мазки, іскри.
        if (cooldown > lastCooldown + 0.5f) {
            hitKick = 1f;
            shockwave = 1f;
            slash = 1f;
            emitRadial(18, 0xFFE4EAF6, 110f, 0.55f);
            emitRadial(8, 0xFFFF6A6A, 70f, 0.45f);
        }
        lastCooldown = cooldown;
        // Перезарядка закінчилась: спалах, хвилі й вибух іскор.
        if (wasReloading && !reloading) {
            readyFlash = 1f;
            emitRadial(26, 0xFFFFFFFF, 95f, 0.7f);
        }
        wasReloading = reloading;

        long ms = System.currentTimeMillis();
        float breathe = 0.5f + 0.5f * (float) Math.sin(ms / 380.0);
        float fade = UiAnim.clamp01(entrance * 1.5f);
        float pop = UiAnim.easeOutBack(UiAnim.clamp01(entrance), 1.0f);

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0f);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, fade);

        // ── Тіло слота (віддача: стиск + тряска) ─────────────────────
        pose.pushPose();
        float sc = (0.55f + 0.45f * pop) * (1f - 0.09f * hitKick) * (1f + 0.05f * readyFlash);
        pose.translate((float) Math.sin(ms / 14.0) * 2.6f * hitKick, 0f, 0f);
        pose.scale(sc, sc, 1f);

        drawGlow(graphics, reloading, breathe);
        drawBase(graphics, reloading, breathe);
        if (reloading) drawCooldownWipe(graphics, progress);
        drawProgressRing(graphics, reloading, progress, breathe);
        if (!reloading) drawComets(graphics, breathe);
        drawTicks(graphics, reloading, progress, ms, entrance);
        drawIcon(graphics, reloading, breathe);
        if (slash > 0.02f) drawSlashes(graphics);

        if (reloading) {
            var font = mc.font;
            String label = String.format(Locale.ROOT, "%.1f", ticksLeft / 20f);
            pose.pushPose();
            pose.scale(1.3f, 1.3f, 1f);
            graphics.drawString(font, label, -font.width(label) / 2, -font.lineHeight / 2 + 1,
                GeneratorFuelUiTheme.TEXT, true);
            pose.popPose();
        }

        drawHitAndReadyFx(graphics);
        pose.popPose();

        // Іскри — у координатах відносно центру слота, без віддачі й масштабу.
        SPARKS.tickAndRender(graphics, dt, 55f);

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        pose.popPose();
    }

    // ── Шари ─────────────────────────────────────────────────────────

    /** М'яке світіння довкола: срібне «дихає» у готовності, червоне — у мить удару. */
    private static void drawGlow(GuiGraphics g, boolean reloading, float breathe) {
        float silver = reloading ? 0.05f : 0.10f + 0.14f * breathe + readyFlash * 0.35f;
        CircleFx.glow(g, 0, 0, R + 4f, 10f, GeneratorFuelUiTheme.alpha(SILVER, silver));
        if (hitKick > 0.02f) {
            CircleFx.glow(g, 0, 0, R + 4f, 14f, GeneratorFuelUiTheme.alpha(DANGER_BRIGHT, hitKick * 0.55f));
        }
    }

    /** Темний диск із радіальним градієнтом (світліший центр), тонка рамка й внутрішній відблиск. */
    private static void drawBase(GuiGraphics g, boolean reloading, float breathe) {
        int center = GeneratorFuelUiTheme.mix(0xF01B2129, 0xF0401418, hitKick * 0.8f);
        int edge = GeneratorFuelUiTheme.mix(0xF00A0C0B, 0xF0300A0E, hitKick * 0.8f);
        CircleFx.disc(g, 0, 0, R, center, edge);
        // Внутрішній відблиск по краю диска (у готовності дихає).
        float a = reloading ? 0.05f : 0.07f + 0.08f * breathe;
        CircleFx.arc(g, 0, 0, R - 8f, R, 0f, TAU, GeneratorFuelUiTheme.alpha(SILVER, a),
            GeneratorFuelUiTheme.alpha(SILVER, a), 0f, 1f);
        // Тонка зовнішня рамка.
        CircleFx.ring(g, 0, 0, R - 0.6f, R + 0.6f, GeneratorFuelUiTheme.alpha(TRACK, 1f));
    }

    /**
     * Годинникова завіса: ще НЕ готова частина диска затемнена й зменшується за
     * стрілкою-променем; за стрілкою тягнеться світлий хвіст-комета.
     */
    private static void drawCooldownWipe(GuiGraphics g, float progress) {
        float head = TOP + TAU * progress;
        float rest = TAU * (1f - progress);
        CircleFx.sector(g, 0, 0, R - 0.8f, head, rest, 0xC4000000, 0xB4000000);

        // Хвіст за стрілкою.
        float tail = Math.min(0.75f, TAU * progress);
        if (tail > 0.02f) {
            CircleFx.arc(g, 0, 0, 3f, R - 0.8f, head - tail, tail,
                GeneratorFuelUiTheme.alpha(SILVER, 0f), GeneratorFuelUiTheme.alpha(SILVER, 0.26f), 0.6f, 1f);
        }
        // Сама стрілка-промінь від центру до краю, звужена до центру.
        float ex = (float) Math.cos(head) * (R - 1f), ey = (float) Math.sin(head) * (R - 1f);
        float sx = (float) Math.cos(head) * 5f, sy = (float) Math.sin(head) * 5f;
        CircleFx.line(g, sx, sy, ex, ey, 0.8f, 2.2f,
            GeneratorFuelUiTheme.alpha(WHITE, 0.25f), GeneratorFuelUiTheme.alpha(WHITE, 0.95f));
    }

    /** Трек + дуга прогресу (перезарядка) або повне «дихаюче» кільце (готовність). */
    private static void drawProgressRing(GuiGraphics g, boolean reloading, float progress, float breathe) {
        float rIn = R - 3.4f, rOut = R - 0.2f;
        CircleFx.ring(g, 0, 0, rIn, rOut, GeneratorFuelUiTheme.alpha(TRACK, 0.95f));
        if (reloading) {
            float sweep = TAU * progress;
            if (sweep > 0.01f) {
                CircleFx.arc(g, 0, 0, rIn, rOut, TOP, sweep,
                    GeneratorFuelUiTheme.alpha(SILVER, 0.45f), WHITE, 1f, 1f);
                // Яскрава голівка з ореолом.
                float a = TOP + sweep;
                float hx = (float) Math.cos(a) * (R - 1.8f), hy = (float) Math.sin(a) * (R - 1.8f);
                CircleFx.disc(g, hx, hy, 5.5f, GeneratorFuelUiTheme.alpha(WHITE, 0.85f),
                    GeneratorFuelUiTheme.alpha(SILVER, 0f));
            }
        } else {
            int c = GeneratorFuelUiTheme.alpha(SILVER, 0.55f + 0.30f * breathe + readyFlash * 0.4f);
            CircleFx.ring(g, 0, 0, rIn, rOut, c);
        }
    }

    /** Готовий слот: дві комети з хвостами біжать у протилежних точках кільця. */
    private static void drawComets(GuiGraphics g, float breathe) {
        float rIn = R - 3.4f, rOut = R - 0.2f;
        float len = 1.25f + 0.25f * breathe;
        for (int k = 0; k < 2; k++) {
            float head = TOP + spin + k * (TAU / 2f);
            CircleFx.arc(g, 0, 0, rIn - 0.6f, rOut + 0.8f, head - len, len,
                GeneratorFuelUiTheme.alpha(WHITE, 0f), GeneratorFuelUiTheme.alpha(WHITE, 0.95f), 1f, 1f);
            float hx = (float) Math.cos(head) * (R - 1.8f), hy = (float) Math.sin(head) * (R - 1.8f);
            CircleFx.disc(g, hx, hy, 4.2f, GeneratorFuelUiTheme.alpha(WHITE, 0.9f),
                GeneratorFuelUiTheme.alpha(SILVER, 0f));
        }
    }

    /**
     * Кільце рисок навколо слота. Перезарядка — риски засвічуються слідом за
     * прогресом; готовність — по рисках ковзає хвиля світла. Під час появи
     * кільце докручується (easeOutCubic).
     */
    private static void drawTicks(GuiGraphics g, boolean reloading, float progress, long ms, float ent) {
        float rot = TOP + (1f - UiAnim.easeOutCubic(UiAnim.clamp01(ent))) * -1.4f;
        float wavePhase = (ms % 1800L) / 1800f;
        float hitBoost = hitKick;
        CircleFx.ticks(g, 0, 0, TICK_IN, TICK_IN + 3f, TICKS, 1.3f, rot, i -> {
            float f = i / (float) TICKS;
            boolean major = i % 4 == 0;
            int base;
            if (reloading) {
                boolean lit = f <= progress;
                float head = Math.max(0f, 1f - Math.abs(f - progress) * 14f); // яскравіше біля стрілки
                base = lit ? GeneratorFuelUiTheme.alpha(GeneratorFuelUiTheme.mix(SILVER, WHITE, head), 0.95f)
                           : GeneratorFuelUiTheme.alpha(0xFF8E97A5, 0.30f);
            } else {
                float d = Math.abs(wavePhase - f);
                d = Math.min(d, 1f - d);
                float wave = Math.max(0f, 1f - d * 9f);
                base = GeneratorFuelUiTheme.alpha(GeneratorFuelUiTheme.mix(SILVER, WHITE, wave),
                    0.35f + 0.65f * wave + readyFlash * 0.4f);
            }
            if (hitBoost > 0.02f) base = GeneratorFuelUiTheme.mix(base, DANGER_BRIGHT, hitBoost * 0.8f);
            return major ? GeneratorFuelUiTheme.mix(base, WHITE, 0.25f) : base;
        });
        // Довші «головні» риски поверх (кожна четверта) — читається ритм годинника.
        CircleFx.ticks(g, 0, 0, TICK_IN + 3f, TICK_IN + 5.5f, TICKS, 1.3f, rot,
            i -> i % 4 == 0 ? GeneratorFuelUiTheme.alpha(reloading ? 0xFF8E97A5 : SILVER, reloading ? 0.45f : 0.75f) : 0);
    }

    /** Кігті по центру: тьмяні на відкаті, яскраві й «дихаючі» у готовності, «пружать» на подіях. */
    private static void drawIcon(GuiGraphics g, boolean reloading, float breathe) {
        float tint = reloading ? 0.36f : Math.min(1f, 0.82f + 0.18f * breathe + readyFlash * 0.4f);
        float s = 1f + 0.22f * readyFlash - 0.14f * hitKick + (reloading ? 0f : 0.03f * breathe);
        var pose = g.pose();
        pose.pushPose();
        pose.scale(s, s, 1f);
        float alpha = UiAnim.clamp01(entrance * 1.5f);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(tint, tint, tint, alpha);
        g.blit(TEX_STRIKE_ICON, -ICON / 2, -ICON / 2, ICON, ICON, 0f, 0f, 32, 32, 32, 32);
        // Множник ОБОВ'ЯЗКОВО назад до загальної прозорості оверлея, інакше решта лишиться тонованою.
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        pose.popPose();
    }

    /** Три мазки кігтів під 45° — прорізаються за ~0.3 с і гаснуть. */
    private static void drawSlashes(GuiGraphics g) {
        float t = UiAnim.clamp01((1f - slash) * 3.2f); // 0..1 — скільки мазка вже прорізано
        float a = slash;
        float[] offsets = { -8f, 0f, 8f };
        float inv = 0.70710678f;
        for (int i = 0; i < offsets.length; i++) {
            float o = offsets[i];
            float lag = UiAnim.clamp01(t * 1.4f - i * 0.18f);
            float x0 = -19f + o * inv, y0 = -19f - o * inv;
            float x1 = x0 + 38f * lag * inv;
            float y1 = y0 + 38f * lag * inv;
            CircleFx.line(g, x0, y0, x1, y1, 2.6f, 0.6f,
                GeneratorFuelUiTheme.alpha(WHITE, a * 0.95f),
                GeneratorFuelUiTheme.alpha(DANGER_BRIGHT, a * 0.75f));
        }
    }

    /** Ударна хвиля (червона) і подвійні хвилі/спалах готовності (срібні/білі). */
    private static void drawHitAndReadyFx(GuiGraphics g) {
        if (shockwave > 0.02f) {
            float k = 1f - shockwave;
            float rr = R + k * 30f;
            CircleFx.arc(g, 0, 0, rr - 1.6f, rr + 1.6f, 0f, TAU,
                GeneratorFuelUiTheme.alpha(DANGER_BRIGHT, shockwave * 0.9f),
                GeneratorFuelUiTheme.alpha(DANGER_BRIGHT, shockwave * 0.9f), 0.25f, 0.25f);
        }
        if (readyFlash > 0.02f) {
            // Внутрішній спалах: яскравий центр → прозорий край.
            CircleFx.disc(g, 0, 0, R - 1f, GeneratorFuelUiTheme.alpha(WHITE, readyFlash * 0.55f),
                GeneratorFuelUiTheme.alpha(WHITE, readyFlash * 0.15f));
            float k = 1f - readyFlash;
            float r1 = R + k * 22f;
            CircleFx.ring(g, 0, 0, r1 - 1.2f, r1 + 1.2f, GeneratorFuelUiTheme.alpha(WHITE, readyFlash));
            float k2 = UiAnim.clamp01((k - 0.22f) / 0.78f);
            if (k2 > 0f && k2 < 1f) {
                float r2 = R + k2 * 30f;
                CircleFx.ring(g, 0, 0, r2 - 1f, r2 + 1f, GeneratorFuelUiTheme.alpha(SILVER, (1f - k2) * 0.8f));
            }
        }
    }

    /** Кільцевий викид іскор від краю диска назовні (координати — відносно центру слота). */
    private static void emitRadial(int n, int argb, float speed, float life) {
        for (int i = 0; i < n; i++) {
            double a = Math.random() * Math.PI * 2.0;
            float s = speed * (0.4f + (float) Math.random() * 0.6f);
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            SPARKS.emit(cos * R, sin * R, cos * s, sin * s,
                life * (0.6f + (float) Math.random() * 0.4f), argb & 0xFFFFFF, Math.random() < 0.3 ? 2 : 1);
        }
    }
}
