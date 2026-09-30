package com.log_to_kot.maniacmod.client.overlay.notify;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.WakeUpAnimation;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.registry.ModSounds;
import com.mojang.math.Axis;
import dev.shaurmalib.forge.sound.SoundCenter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Оголошення ролі на початку матчу — повноекранний текстовий оверлей БЕЗ
 * фонових плашок, ~5 секунд, з появою й зникненням.
 *
 * ── Хто що бачить ────────────────────────────────────────────────────
 *   • ВИЖИВШИЙ — «ВАША РОЛЬ / ВИЖИВШИЙ» угорі й «ВАШЕ ЗАВДАННЯ / ВТЕКТИ»
 *     внизу. Стартує одразу на вході в ROLE_REVEAL (анімацію
 *     вставання прибрано), після кіно-вступу.
 *   • МАНЬЯК — «ВАША РОЛЬ» + назва його архетипу й іконки пасток, які він
 *     взяв у матч. Стартує, коли прийшов набір пасток
 *     ({@link #onTrapLoadout}) — тобто після вибору в
 *     {@code TrapChooseScreen}, або автовибору на вході в ігрову фазу,
 *     якщо вибирати не було з чого.
 *
 * ── Звідки дані ──────────────────────────────────────────────────────
 * Нового пакета немає: роль і архетип — {@link ClientMatchState}
 * ({@code RoleSyncPacket}), пастки — {@code TrapLoadoutPacket}, кінець
 * пробудження — {@link WakeUpAnimation}. Оверлей нічого не вирішує в матчі.
 *
 * ── Одноразовість ────────────────────────────────────────────────────
 * Вхід у {@code ROLE_REVEAL} «заряджає» показ ({@link #onPhaseChanged}),
 * перший же запуск його «розряджає». Тому повторний
 * {@code TrapLoadoutPacket} (ресинк на вході в HUNT) чи реконект посеред
 * матчу оверлей повторно не викликають. Скидається в
 * {@link ClientMatchState#reset()} — як і решта клієнтського стану.
 *
 * ── Таймінг ──────────────────────────────────────────────────────────
 * Від {@code System.currentTimeMillis()}, а не від тіків — з тієї самої
 * причини, що в {@link GeneratorCompletedOverlay}: показ має однаково
 * йти, коли відкрите будь-яке меню. Тривалість НЕ прив'язана до фази:
 * оверлей доживає свої ~5 с навіть якщо {@code ROLE_REVEAL} уже скінчився.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RoleRevealOverlay {

    // ── Таймлайн, мс від старту ──────────────────────────────────────────
    private static final long TOTAL_MS = 5200;
    private static final long OUT_MS = 1000;
    private static final long OUT_START_MS = TOTAL_MS - OUT_MS;
    private static final long OUT_LETTER_SPREAD_MS = 380;

    private static final long LABEL_START_MS = 0;
    private static final long LABEL_IN_MS = 900;
    private static final long NAME_START_MS = 350;
    private static final long LETTER_MS = 400;
    private static final float LETTER_STAGGER_MS = 75f;
    private static final float NAME_SPREAD_MS = 700f;
    private static final long LINE_START_MS = 900;
    private static final long LINE_MS = 800;
    private static final long TASK_START_MS = 1150;
    private static final long TASK_IN_MS = 900;
    private static final long TRAPS_START_MS = 1250;
    private static final long TRAP_STAGGER_MS = 170;
    private static final long TRAP_IN_MS = 520;
    private static final long GLINT_START_MS = 1500;
    private static final long GLINT_PERIOD_MS = 2400;
    private static final long GLINT_SWEEP_MS = 900;
    private static final long HEART_PERIOD_MS = 860;

    // ── Розкладка (частки висоти екрана / базові масштаби при висоті 540) ─
    private static final float LABEL_Y = 0.235f;
    private static final float NAME_Y = 0.355f;
    private static final float TASK_LABEL_Y = 0.835f;
    private static final float TASK_Y = 0.885f;
    private static final float TRAPS_LABEL_Y = 0.775f;
    private static final float TRAPS_Y = 0.855f;
    private static final float LABEL_SCALE = 1.9f;
    private static final float LABEL_TRACK = 2.4f;
    private static final float NAME_SCALE = 6f;
    private static final float NAME_TRACK = 0.6f;
    private static final float TASK_SCALE = 3.4f;
    private static final float ICON_SIZE = 34f;
    private static final float ICON_GAP = 24f;

    // ── Об'ємний шрифт: екструзія + контур, у текстових одиницях ─────────
    private static final int EXTRUDE_LAYERS = 4;
    private static final float EXTRUDE_STEP = 0.14f;
    private static final float OUTLINE_R = 0.2f;

    private static final float AMBIENT_RATE = 26f;
    private static final float OUTRO_RATE = 70f;
    private static final String SCRAMBLE = "АБВГДЕЖЗИКЛМНОПРСТУФХЦЧШЩЯ0123456789#%";
    private static final int MAX_TRAP_ICONS = 8;

    private record Palette(int fill, int extrude, int outline, int accent, int ghost, int vignette) {}

    private static final Palette SURVIVOR = new Palette(
        0xFF34D7FF, 0xFF0B3F8C, 0xFF04091C, 0xFF34D7FF, 0xFFFF2A55, 0x22B8F5);
    private static final Palette MANIAC = new Palette(
        0xFFE0242B, 0xFF4A0508, 0xFF0A0000, 0xFFFF3B3B, 0xFF2AF0FF, 0xC9121A);

    private enum Mode { SURVIVOR, MANIAC }

    private record TrapEntry(String name, ResourceLocation icon) {}

    // ── Стан ─────────────────────────────────────────────────────────────
    private static boolean armed = false;
    /** Показ виживого вже поставлено в чергу (щоб не планувати щотіка). */
    private static boolean survivorQueued = false;
    private static boolean active = false;
    /** Набір пасток, що прийшов РАНІШЕ за фазу ROLE_REVEAL (порядок пакетів не гарантований). */
    private static List<String> earlyLoadout = null;

    private static Mode mode = Mode.SURVIVOR;
    private static long startedAtMs = 0L;
    private static int seed = 0;
    private static String labelRole = "";
    private static String labelBottom = "";
    private static String roleName = "";
    private static String taskText = "";
    private static List<TrapEntry> traps = List.of();

    private static boolean impacted = false;
    private static boolean dubPlayed = false;
    private static boolean outroBeat = false;
    private static long dubAtMs = 0L;
    private static float emitAcc = 0f;
    private static final boolean[] iconBurst = new boolean[MAX_TRAP_ICONS];

    private static final UiFx.Sparks sparks = new UiFx.Sparks(240);
    private static final RoleRevealFx.Blood blood = new RoleRevealFx.Blood();
    private static final long[] lastFrameNanos = new long[1];

    private RoleRevealOverlay() {}

    // ── Запуск / скидання ────────────────────────────────────────────────

    /** З {@code ClientMatchState.setPhase}: вхід у ROLE_REVEAL заряджає показ. */
    public static void onPhaseChanged(GamePhase prev, GamePhase next) {
        if (next == GamePhase.ROLE_REVEAL && prev != GamePhase.ROLE_REVEAL) {
            armed = true;
            survivorQueued = false;
            active = false;
            sparks.clear();
            blood.clear();
            List<String> early = earlyLoadout;
            earlyLoadout = null;
            if (early != null) onTrapLoadout(early);
        }
    }

    /** З {@code ClientPacketHandler.onTrapLoadout}: набір пасток маньяка прийшов. */
    public static void onTrapLoadout(List<String> trapIds) {
        if (trapIds.isEmpty() || !ClientMatchState.isManiac()) return;
        if (!armed) {
            GamePhase ph = ClientMatchState.phase();
            if (ph == GamePhase.CINEMATIC || ph == GamePhase.SCATTER) earlyLoadout = List.copyOf(trapIds);
            return;
        }

        List<TrapEntry> entries = new ArrayList<>();
        for (String id : trapIds) {
            if (entries.size() >= MAX_TRAP_ICONS) break;
            entries.add(new TrapEntry(
                trapName(id),
                new ResourceLocation(ManiacMod.MOD_ID, "textures/gui/traps/" + id + ".png")));
        }
        traps = List.copyOf(entries);
        roleName = upper(maniacName(ClientMatchState.archetypeId()));
        taskText = "";
        labelBottom = upper(I18n.get("maniacmod.reveal.label.traps"));
        // Не перекриваємо фінальне розтемнення вступу: стартуємо, щойно воно скінчиться.
        com.log_to_kot.maniacmod.client.intro.IntroClient.runWhenUnblocked(() -> {
            if (armed) begin(Mode.MANIAC);
        });
    }

    /**
     * Виживий: оголошення ролі стартує одразу на вході в ROLE_REVEAL (анімації
     * вставання більше немає), але не раніше, ніж закінчиться кіно-вступ.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!armed || survivorQueued) return;
        if (!ClientMatchState.isSurvivor()) return;

        survivorQueued = true;
        com.log_to_kot.maniacmod.client.intro.IntroClient.runWhenUnblocked(() -> {
            if (!armed) return;
            roleName = upper(I18n.get("maniacmod.reveal.role.survivor"));
            taskText = upper(I18n.get("maniacmod.reveal.task.survivor"));
            labelBottom = upper(I18n.get("maniacmod.reveal.label.task"));
            traps = List.of();
            begin(Mode.SURVIVOR);
        });
    }

    private static void begin(Mode newMode) {
        armed = false;
        active = true;
        mode = newMode;
        long now = System.currentTimeMillis();
        startedAtMs = now;
        seed = (int) (now & 0xFFFF);
        labelRole = upper(I18n.get("maniacmod.reveal.label.role"));
        impacted = false;
        dubPlayed = false;
        outroBeat = false;
        emitAcc = 0f;
        lastFrameNanos[0] = 0L;
        Arrays.fill(iconBurst, false);
        sparks.clear();
        blood.start(seed);
    }

    /** Викликається з {@code ClientMatchState.reset()}. */
    public static void reset() {
        armed = false;
        survivorQueued = false;
        active = false;
        earlyLoadout = null;
        traps = List.of();
        sparks.clear();
        blood.clear();
    }

    // ── Рендер ───────────────────────────────────────────────────────────

    public static void render(GuiGraphics g) {
        if (!active) return;
        long t = System.currentTimeMillis() - startedAtMs;
        if (t >= TOTAL_MS) {
            active = false;
            sparks.clear();
            blood.clear();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || roleName.isEmpty()) return;

        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        float k = Math.max(0.5f, Math.min(1.8f, sh / 540f));
        float dt = UiAnim.frameDt(lastFrameNanos);
        Palette pal = mode == Mode.MANIAC ? MANIAC : SURVIVOR;

        float outQ = UiAnim.clamp01((t - OUT_START_MS) / (float) OUT_MS);
        float fade = 1f - UiAnim.easeInOutCubic(outQ);

        // ── Розкладка імені ──────────────────────────────────────────────
        int n = roleName.length();
        Component[] letter = new Component[n];
        float[] adv = new float[n];
        float rawW = 0f;
        for (int i = 0; i < n; i++) {
            letter[i] = bold(String.valueOf(roleName.charAt(i)));
            adv[i] = font.width(letter[i]);
            rawW += adv[i] + (i < n - 1 ? NAME_TRACK : 0f);
        }
        float nameScale = Math.min(NAME_SCALE * k, 0.74f * sw / Math.max(1f, rawW));
        float nameH = font.lineHeight * nameScale;
        float nameLeft = sw / 2f - rawW * nameScale / 2f;
        float nameTop = sh * NAME_Y - nameH / 2f;

        float stagger = n > 1 ? Math.min(LETTER_STAGGER_MS, NAME_SPREAD_MS / (n - 1f)) : 0f;
        long impactAt = NAME_START_MS + (long) (stagger * (n - 1)) + (long) (LETTER_MS * 0.7f);

        // ── Звук і удар «приземлення» імені ──────────────────────────────
        long now = System.currentTimeMillis();
        if (!impacted && t >= impactAt) {
            impacted = true;
            dubAtMs = now + 230;
            playHeart(ModSounds.HEARTBEAT_OUT, 1.0f, 0.78f);
            float cx = sw / 2f, cy = nameTop + nameH * 0.55f;
            sparks.burst(cx, cy, 34, 150f, 0f, -1f, 0.95f, pal.accent);
            sparks.burst(nameLeft, cy, 12, 110f, -1f, -0.2f, 0.7f, pal.accent);
            sparks.burst(nameLeft + rawW * nameScale, cy, 12, 110f, 1f, -0.2f, 0.7f, pal.accent);
        }
        if (impacted && !dubPlayed && now >= dubAtMs) {
            dubPlayed = true;
            playHeart(ModSounds.HEARTBEAT_IN, 0.85f, 0.82f);
        }
        if (!outroBeat && t >= OUT_START_MS) {
            outroBeat = true;
            playHeart(ModSounds.HEARTBEAT_OUT, 0.55f, 0.7f);
        }

        // ── Глобальні ефекти імені: тряска, глітч, пульс серця, мерехтіння ─
        float ox = 0f, oy = 0f;
        long sinceImpact = t - impactAt;
        if (sinceImpact >= 0 && sinceImpact < 380) {
            float amp = (float) Math.pow(1f - sinceImpact / 380f, 2) * 4f * k;
            ox = (hash01(t / 16 * 3L + seed) - 0.5f) * 2f * amp;
            oy = (hash01(t / 16 * 5L + seed + 11) - 0.5f) * 2f * amp;
        }
        float glitch = glitchAt(t, impactAt, outQ);
        float heart = 1f;
        if (t > impactAt) {
            float ph = ((t - impactAt) % HEART_PERIOD_MS) / (float) HEART_PERIOD_MS;
            heart = 1f + 0.022f * (bump(ph, 0.06f, 0.05f) + 0.65f * bump(ph, 0.27f, 0.05f));
        }
        float flick = (t > impactAt + 600 && t < OUT_START_MS
            && hash01((t / 55) * 13L + seed) > 0.968f) ? 0.5f : 1f;

        // ── Тло під текстом: віньєтка кольору ролі, спалах удару, кров ────
        // Віньєтка «дихає» в такт серцю (той самий пульс, що в імені), на ударі
        // імені різко спалахує й гасне. У маньяка поверх неї стікає кров.
        float bgIn = UiAnim.easeOutCubic(UiAnim.clamp01(t / 900f));
        float beat = 0f;
        if (t > impactAt) {
            float ph = ((t - impactAt) % HEART_PERIOD_MS) / (float) HEART_PERIOD_MS;
            beat = bump(ph, 0.06f, 0.05f) + 0.65f * bump(ph, 0.27f, 0.05f);
        }
        float thump = (sinceImpact >= 0 && sinceImpact < 520)
            ? (float) Math.pow(1f - sinceImpact / 520f, 2) : 0f;
        float vig = (0.58f + 0.20f * beat + 0.35f * thump) * bgIn * fade;
        RoleRevealFx.vignette(g, sw, sh, 0x000000, vig, 100);
        RoleRevealFx.vignette(g, sw, sh, pal.vignette, vig, 130);
        RoleRevealFx.flash(g, sw, sh, pal.vignette, 0.20f * thump * fade);
        if (mode == Mode.MANIAC) {
            blood.render(g, sw, sh, k, t, dt, fade);
        }

        // ── Підпис «ВАША РОЛЬ» ───────────────────────────────────────────
        float labelP = UiAnim.easeOutCubic(UiAnim.clamp01((t - LABEL_START_MS) / (float) LABEL_IN_MS));
        drawLabel(g, font, labelRole, sw / 2f, sh * LABEL_Y - (1f - labelP) * 8f * k,
            LABEL_SCALE * k, LABEL_TRACK * (0.2f + 0.8f * labelP) + outQ * 4f, labelP * fade);

        // ── Ім'я по літерах ──────────────────────────────────────────────
        float cursor = 0f;
        long glintT = t - GLINT_START_MS;
        float sweepPos = glintT >= 0 ? (glintT % GLINT_PERIOD_MS) / (float) GLINT_SWEEP_MS : 9f;
        float glintCenter = sweepPos * 1.3f - 0.15f;
        for (int i = 0; i < n; i++) {
            float w = adv[i];
            float centerText = cursor + w / 2f;
            cursor += w + NAME_TRACK;

            long start = NAME_START_MS + (long) (stagger * i);
            float p = UiAnim.clamp01((t - start) / (float) LETTER_MS);
            if (p <= 0f) continue;
            if (roleName.charAt(i) == ' ') continue;

            float rnd = hash01(seed * 131L + i * 17L + 3L);
            float lq = UiAnim.clamp01(
                (t - OUT_START_MS - rnd * OUT_LETTER_SPREAD_MS) / (float) (OUT_MS - OUT_LETTER_SPREAD_MS));
            float pin = UiAnim.easeOutCubic(p);
            float s = 1f + (1f - pin) * 1.4f + lq * 0.18f;
            float a = UiAnim.clamp01(p * 2.4f) * (1f - lq) * flick;
            if (a <= 0.02f) continue;

            float jx = (hash01(i * 17L + t / 50 + seed) - 0.5f) * glitch * 4f * k;
            float jy = (hash01(i * 29L + t / 50 + seed + 7) - 0.5f) * glitch * 2f * k;
            float lx = nameLeft + centerText * nameScale + ox + jx + (rnd - 0.5f) * lq * 18f * k;
            float ly = nameTop + nameH / 2f + oy + jy - lq * 26f * k;

            float norm = centerText / rawW;
            float br = Math.max(0f, 1f - Math.abs(norm - glintCenter) * 7f);
            int fill = lerpRgb(pal.fill, 0xFFFFFF, br * 0.8f);

            float sc = nameScale * s * heart;
            float x = lx - w * sc / 2f;
            float y = ly - font.lineHeight * sc / 2f;

            if (p < 1f) {
                float sc2 = nameScale * (s + 0.35f * (1f - p));
                drawPlain(g, font, letter[i], lx - w * sc2 / 2f, ly - font.lineHeight * sc2 / 2f,
                    sc2, pal.accent, a * (1f - p) * 0.45f);
            }
            drawFat(g, font, letter[i], x, y, sc, fill, pal.extrude, pal.outline, a,
                EXTRUDE_LAYERS, EXTRUDE_STEP, OUTLINE_R);
            if (glitch > 0.02f) {
                float gx = glitch * 3.2f * k;
                drawPlain(g, font, letter[i], x - gx, y, sc, pal.ghost, a * 0.55f * glitch);
                drawPlain(g, font, letter[i], x + gx, y, sc, 0xFFFFFF, a * 0.35f * glitch);
            }
        }

        // ── Тонка лінія під іменем ───────────────────────────────────────
        float lineP = UiAnim.easeOutCubic(UiAnim.clamp01((t - LINE_START_MS) / (float) LINE_MS));
        drawFadeLine(g, sw / 2f + ox, nameTop + nameH + 8f * k + oy,
            rawW * nameScale * 0.95f * lineP, pal.accent, fade);

        // ── Нижній блок: завдання (виживий) або пастки (маньяк) ──────────
        float bottomLabelA = UiAnim.clamp01((t - (mode == Mode.MANIAC ? TRAPS_START_MS : TASK_START_MS)) / 300f) * fade;
        if (mode == Mode.SURVIVOR) {
            drawLabel(g, font, labelBottom, sw / 2f, sh * TASK_LABEL_Y,
                LABEL_SCALE * 0.8f * k, LABEL_TRACK * 0.8f + outQ * 3f, bottomLabelA);
            float scr = UiAnim.clamp01((t - TASK_START_MS) / (float) TASK_IN_MS)
                * (1f - UiAnim.clamp01(outQ * 1.5f));
            drawScramble(g, font, taskText, sw / 2f, sh * TASK_Y, TASK_SCALE * k, scr, t, pal, fade);
        } else {
            drawLabel(g, font, labelBottom, sw / 2f, sh * TRAPS_LABEL_Y,
                LABEL_SCALE * 0.8f * k, LABEL_TRACK * 0.8f + outQ * 3f, bottomLabelA);
            drawTraps(g, font, sw, sh, k, t, fade, pal);
        }

        // ── Іскри: дрейфують угору від імені, на виході — густіше ────────
        float rate = t < OUT_START_MS ? AMBIENT_RATE : OUTRO_RATE;
        if (t > NAME_START_MS + 300) {
            emitAcc += dt * rate;
            int guard = 6;
            while (emitAcc >= 1f && guard-- > 0) {
                emitAcc -= 1f;
                float r1 = (float) Math.random(), r2 = (float) Math.random(), r3 = (float) Math.random();
                sparks.emit(nameLeft + r1 * rawW * nameScale, nameTop + nameH * (0.35f + 0.6f * r2),
                    (r3 - 0.5f) * 10f, -(14f + r1 * 26f), 1.1f + r2, pal.accent, r3 < 0.3f ? 2 : 1);
            }
            if (emitAcc >= 1f) emitAcc = 0f;
        }
        sparks.tickAndRender(g, dt, 12f);
    }

    // ── Пастки маньяка ───────────────────────────────────────────────────

    private static void drawTraps(GuiGraphics g, Font font, int sw, int sh, float k,
                                  long t, float fade, Palette pal) {
        int count = traps.size();
        if (count == 0) return;
        float icon = ICON_SIZE * k;
        float gap = ICON_GAP * k;
        float total = count * icon + (count - 1) * gap;
        float startX = sw / 2f - total / 2f;

        for (int j = 0; j < count; j++) {
            float start = TRAPS_START_MS + j * TRAP_STAGGER_MS;
            float p = UiAnim.clamp01((t - start) / (float) TRAP_IN_MS);
            if (p <= 0f) continue;

            float cx = startX + j * (icon + gap) + icon / 2f;
            float cy = sh * TRAPS_Y + (float) Math.sin((t + j * 260L) / 520.0) * 1.6f * k;
            float sc = 0.35f + 0.65f * UiAnim.easeOutBack(p, 1.2f);
            float rot = (1f - UiAnim.easeOutCubic(p)) * (j % 2 == 0 ? -24f : 24f);
            float a = UiAnim.clamp01(p * 2.5f) * fade;

            if (p >= 0.6f && j < MAX_TRAP_ICONS && !iconBurst[j]) {
                iconBurst[j] = true;
                sparks.burst(cx, cy, 12, 70f, 0f, -1f, 0.6f, pal.accent);
            }

            var pose = g.pose();
            pose.pushPose();
            pose.translate(cx, cy, 0f);
            pose.mulPose(Axis.ZP.rotationDegrees(rot));
            pose.scale(sc * icon / 32f, sc * icon / 32f, 1f);
            // «Світіння» без прямокутників: ті самі іконки, зсунуті на 2 px, з малою альфою.
            for (int d = 0; d < 4; d++) {
                int dx = d == 0 ? -2 : d == 1 ? 2 : 0;
                int dy = d == 2 ? -2 : d == 3 ? 2 : 0;
                UiFx.blitTinted(g, traps.get(j).icon(), -16 + dx, -16 + dy, 0f, 0f, 32, 32, 32, 32,
                    pal.accent, a * 0.28f);
            }
            UiFx.blitTinted(g, traps.get(j).icon(), -16, -16, 0f, 0f, 32, 32, 32, 32, 0xFFE9E9, a);
            pose.popPose();

            float nameA = a * UiAnim.clamp01((p - 0.4f) / 0.6f);
            Component nm = Component.literal(upper(traps.get(j).name()));
            float nmScale = 1.0f * k;
            drawFat(g, font, nm, cx - font.width(nm) * nmScale / 2f, cy + icon / 2f + 6f * k, nmScale,
                0xFFE6E6E6, 0xFF000000, pal.outline, nameA, 0, 0f, 0.5f);
        }
    }

    // ── Текстові примітиви ───────────────────────────────────────────────

    private static Component bold(String s) {
        return Component.literal(s).withStyle(ChatFormatting.BOLD);
    }

    /** Підпис із розрідженням: біла заливка, чорний контур, центр у (cx, cy). */
    private static void drawLabel(GuiGraphics g, Font font, String text, float cx, float cy,
                                  float scale, float track, float alpha) {
        if (alpha < 0.03f || text.isEmpty()) return;
        int n = text.length();
        Component[] cs = new Component[n];
        float[] w = new float[n];
        float total = 0f;
        for (int i = 0; i < n; i++) {
            cs[i] = bold(String.valueOf(text.charAt(i)));
            w[i] = font.width(cs[i]);
            total += w[i] + (i < n - 1 ? track : 0f);
        }
        float x = cx - total * scale / 2f;
        float y = cy - font.lineHeight * scale / 2f;
        float cursor = 0f;
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) != ' ') {
                drawFat(g, font, cs[i], x + cursor * scale, y, scale,
                    0xFFFFFFFF, 0xFF000000, 0xFF000000, alpha, 0, 0f, 0.3f);
            }
            cursor += w[i] + track;
        }
    }

    /** Текст, що «розшифровується» з випадкових символів (і так само зникає). */
    private static void drawScramble(GuiGraphics g, Font font, String text, float cx, float cy,
                                     float scale, float progress, long t, Palette pal, float alpha) {
        int n = text.length();
        if (n == 0 || alpha < 0.03f) return;
        Component[] fin = new Component[n];
        float[] w = new float[n];
        float total = 0f;
        for (int i = 0; i < n; i++) {
            fin[i] = bold(String.valueOf(text.charAt(i)));
            w[i] = font.width(fin[i]);
            total += w[i] + (i < n - 1 ? 1f : 0f);
        }
        float left = cx - total * scale / 2f;
        float y = cy - font.lineHeight * scale / 2f;
        float cursor = 0f;
        for (int i = 0; i < n; i++) {
            float th = n > 1 ? (i / (n - 1f)) * 0.6f : 0f;
            char ch = text.charAt(i);
            float cell = w[i];
            if (ch != ' ' && progress >= th) {
                boolean settled = progress >= th + 0.34f;
                Component c = fin[i];
                if (!settled) {
                    int idx = (int) (hash01(seed * 977L + i * 31L + t / 55) * SCRAMBLE.length());
                    c = bold(String.valueOf(SCRAMBLE.charAt(Math.min(idx, SCRAMBLE.length() - 1))));
                }
                float cw = font.width(c);
                drawFat(g, font, c, left + (cursor + (cell - cw) / 2f) * scale, y, scale,
                    settled ? 0xFFF4F7FF : pal.accent, 0xFF20252E, 0xFF000000,
                    alpha * (settled ? 1f : 0.85f), 3, 0.10f, 0.2f);
            }
            cursor += cell + 1f;
        }
    }

    /** Об'ємний текст: екструзія вниз-вправо, контур у 8 напрямках, заливка. (x,y) — лівий верх, екранні одиниці. */
    private static void drawFat(GuiGraphics g, Font font, Component c, float x, float y, float scale,
                                int fill, int extrude, int outline, float alpha,
                                int extrudeLayers, float extrudeStep, float outlineR) {
        if (alpha < 0.035f) return;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0f);
        pose.scale(scale, scale, 1f);
        int ec = argb(extrude, alpha);
        for (int i = extrudeLayers; i >= 1; i--) {
            layer(g, font, c, i * extrudeStep, i * extrudeStep, ec);
        }
        int oc = argb(outline, alpha);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                layer(g, font, c, dx * outlineR, dy * outlineR, oc);
            }
        }
        layer(g, font, c, 0f, 0f, argb(fill, alpha));
        pose.popPose();
    }

    /** Одна заливка без контуру — для відлуння й RGB-розщеплення. */
    private static void drawPlain(GuiGraphics g, Font font, Component c, float x, float y,
                                  float scale, int rgb, float alpha) {
        if (alpha < 0.035f) return;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0f);
        pose.scale(scale, scale, 1f);
        layer(g, font, c, 0f, 0f, argb(rgb, alpha));
        pose.popPose();
    }

    private static void layer(GuiGraphics g, Font font, Component c, float dx, float dy, int color) {
        var pose = g.pose();
        pose.pushPose();
        pose.translate(dx, dy, 0f);
        g.drawString(font, c, 0, 0, color, false);
        pose.popPose();
    }

    /** Тонка лінія від центру, що гасне до країв (плюс слабке світіння вище й нижче). */
    private static void drawFadeLine(GuiGraphics g, float cx, float y, float width,
                                     int rgb, float alpha) {
        if (width < 4f || alpha < 0.03f) return;
        float half = width / 2f;
        int yi = Math.round(y);
        for (float xi = -half; xi < half; xi += 2f) {
            float edge = 1f - Math.abs(xi) / half;
            float a = (float) Math.pow(edge, 0.7) * alpha;
            int x0 = Math.round(cx + xi);
            g.fill(x0, yi, x0 + 2, yi + 1, argb(rgb, a));
            g.fill(x0, yi - 1, x0 + 2, yi, argb(rgb, a * 0.22f));
            g.fill(x0, yi + 1, x0 + 2, yi + 2, argb(rgb, a * 0.22f));
        }
    }

    // ── Дрібні помічники ─────────────────────────────────────────────────

    /**
     * Глітч 0..1: спалах при «приземленні», рідкісні випадкові сплески в
     * утриманні й наростання на виході. Детермінований від часу й seed.
     */
    private static float glitchAt(long t, long impactAt, float outQ) {
        float gl = 0f;
        long since = t - impactAt;
        if (since >= 0 && since < 330) gl = 1f - since / 330f;
        long bucket = t / 130;
        if (t > impactAt + 900 && t < OUT_START_MS && hash01(bucket * 7919L + seed) > 0.93f) {
            gl = Math.max(gl, 0.35f + 0.65f * hash01(bucket * 31L + seed));
        }
        if (outQ > 0f) gl = Math.max(gl, outQ * 0.8f * (0.5f + 0.5f * hash01(bucket * 17L + seed)));
        return gl;
    }

    private static float bump(float x, float center, float width) {
        float d = (x - center) / width;
        return (float) Math.exp(-d * d);
    }

    private static float hash01(long v) {
        v ^= v >>> 33;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 33;
        v *= 0xc4ceb9fe1a85ec53L;
        v ^= v >>> 33;
        return (v >>> 40) / (float) (1L << 24);
    }

    private static int lerpRgb(int a, int b, float t) {
        float k = UiAnim.clamp01(t);
        int r = Math.round(((a >> 16) & 0xFF) * (1f - k) + ((b >> 16) & 0xFF) * k);
        int gr = Math.round(((a >> 8) & 0xFF) * (1f - k) + ((b >> 8) & 0xFF) * k);
        int bl = Math.round((a & 0xFF) * (1f - k) + (b & 0xFF) * k);
        return (r << 16) | (gr << 8) | bl;
    }

    /**
     * ARGB із альфою в старшому байті. Для тексту альфа нижче 4 ванільний шрифт
     * вважає «непрозорим», тому викликачі відсікають майже нульову альфу раніше.
     */
    private static int argb(int rgb, float alpha) {
        int a = Math.round(UiAnim.clamp01(alpha) * 255f);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    private static String maniacName(String archetypeId) {
        if (!archetypeId.isEmpty() && ManiacRegistry.exists(archetypeId)) {
            return ManiacRegistry.get(archetypeId).displayName();
        }
        return I18n.get("maniacmod.reveal.role.maniac");
    }

    /** Назва пастки з lang; якщо ключа ще нема — сам id без підкреслень. */
    private static String trapName(String id) {
        String key = "maniacmod.trap.name." + id;
        return I18n.exists(key) ? I18n.get(key) : id.replace('_', ' ');
    }

    private static void playHeart(RegistryObject<SoundEvent> sound, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        SoundCenter.playAt(sound.getId().toString(), SoundSource.PLAYERS, volume, pitch,
            mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }
}
