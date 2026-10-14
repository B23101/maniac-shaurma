package com.log_to_kot.maniacmod.client.voice;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.voice.VoiceIndicatorState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * ЄДИНИЙ індикатор голосу в моді: ОДНА іконка в HUD про ВЛАСНИЙ стан — без панелі й без тексту.
 * Над головою гравців голосових значків немає взагалі (штатні гасить {@code MixinSvcRenderEventsIcons}).
 *
 * ── Мікрофон реагує на голос ─────────────────────────────────────────
 * Рівень береться з реальних кадрів мікрофона ({@link VoiceIndicatorState#ownLevel()}, RMS у dB):
 *   • капсула мікрофона ЗАПОВНЮЄТЬСЯ знизу вгору залежно від гучності — як шкала рівня;
 *   • по боках з'являються звукові хвилі: перша від помірного голосу, друга від гучного, на піку «пульсують»;
 *   • колір світлішає від срібного до білого.
 * Рівень згладжений: швидка атака, повільне затухання.
 *
 * ── Стани (лише іконка) ──────────────────────────────────────────────
 *   говорю — срібний мікрофон, 2 хвилі; шепіт — сірий, 1 хвиля; мікрофон вимкнено — сталевий мікрофон із
 *   червоним перекресленням; звук вимкнено — динамік із червоним перекресленням; голос відключено —
 *   динамік із бурштиновим перекресленням, що блимає.
 *
 * ── Стиль ────────────────────────────────────────────────────────────
 * Палітра мода ({@link GeneratorFuelUiTheme}: срібло, небезпека-червоний, попередження-бурштин) і темний
 * контур замість панелі, щоб іконка читалась на будь-якому тлі. Піксельні маски в коді, текстур немає.
 * Поява/зникнення — виїзд із краю ({@link UiAnim}).
 *
 * ── Де ───────────────────────────────────────────────────────────────
 * Лівий край екрана по вертикалі в центрі (решта кутів зайнята HUD мода). Позиція — {@link #MARGIN_LEFT}
 * і {@link #centerY}; розмір — {@link #SCALE}.
 *
 * Реєстрація — {@code ClientSetup.onRegisterOverlays}; під час вступу гасне разом з усім HUD.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
@OnlyIn(Dist.CLIENT)
public final class VoiceHudOverlay {

    /** Відступ панелі від лівого краю екрана, px. */
    private static final int MARGIN_LEFT = 10;
    /** Масштаб піксельної іконки (1 піксель маски = SCALE px GUI). */
    private static final int SCALE = 2;

    /** Область піктограми: 11×11 маска посередині + по 4px з боків під хвилі. */
    private static final int GLYPH_W = 19;
    private static final int GLYPH_H = 11;
    private static final int MASK_X = 4;
    private static final int CAPSULE_ROWS = 7;          // рядки 0..6 маски мікрофона — капсула
    private static final int CAPSULE_C0 = 3, CAPSULE_C1 = 7;

    private static final int STEEL_EMPTY = 0xFF39414D;  // незаповнена частина капсули

    // Піксельні маски 11×11: 'X' — зафарбований піксель.
    private static final String[] MIC = {
            "....XXX....",
            "...XXXXX...",
            "...XXXXX...",
            "...XXXXX...",
            "...XXXXX...",
            ".X.XXXXX.X.",
            ".X..XXX..X.",
            "..X.....X..",
            "...XXXXX...",
            ".....X.....",
            "....XXX....",
    };
    private static final String[] SPEAKER = {
            "...........",
            "....X......",
            "...XX...X..",
            ".XXXX....X.",
            ".XXXX..X.X.",
            ".XXXX..X.X.",
            ".XXXX....X.",
            "...XX...X..",
            "....X......",
            "...........",
            "...........",
    };

    private enum Look {
        //   піктограма, перекреслення, колір, хвиль, блимає
        TALK(MIC, false, GeneratorFuelUiTheme.FILL, 2, false),
        WHISPER(MIC, false, GeneratorFuelUiTheme.HIT_ZONE, 1, false),
        MUTED(MIC, true, GeneratorFuelUiTheme.FILL_DANGER, 0, false),
        DEAFENED(SPEAKER, true, GeneratorFuelUiTheme.FILL_DANGER, 0, false),
        DISCONNECTED(SPEAKER, true, GeneratorFuelUiTheme.FILL_WARN, 0, true);

        final String[] glyph;
        final boolean slashed;
        final int color;
        final int waves;
        final boolean blink;

        Look(String[] glyph, boolean slashed, int color, int waves, boolean blink) {
            this.glyph = glyph;
            this.slashed = slashed;
            this.color = color;
            this.waves = waves;
            this.blink = blink;
        }

        boolean live() {
            return waves > 0;
        }
    }

    // Плавна поява — той самий підхід, що в підказок генератора.
    private static final long[] lastFrameNanos = new long[1];
    private static float appear = 0f;
    /** Згладжена гучність 0..1: швидка атака, повільне затухання. */
    private static float level = 0f;
    /** Останній показаний вигляд: тримається, поки іконка з'їжджає, щоб вона не міняла вигляд посеред зникнення. */
    private static Look shown = null;

    private VoiceHudOverlay() {}

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();

        Look want = (mc.options.hideGui || VoiceIndicatorState.iconsHidden()) ? null : lookFor(VoiceIndicatorState.own());
        if (want != null) shown = want;

        float dt = UiAnim.frameDt(lastFrameNanos);
        appear = UiAnim.approach(appear, want != null ? 1f : 0f, want != null ? 12f : 10f, dt);
        float target = want != null && want.live() ? VoiceIndicatorState.ownLevel() : 0f;
        level = UiAnim.approach(level, target, target > level ? 30f : 6f, dt);

        if (appear < 0.01f || shown == null) {
            appear = 0f;
            return;
        }
        float ease = UiAnim.easeOutCubic(UiAnim.clamp01(appear));

        Look look = shown;
        int w = GLYPH_W * SCALE, h = GLYPH_H * SCALE;
        int x = MARGIN_LEFT;
        int y = centerY(mc) - h / 2;

        g.pose().pushPose();
        // Виїзд із лівого краю: на ease=0 іконка повністю за екраном.
        g.pose().translate(-(1f - ease) * (w + MARGIN_LEFT), 0f, 0f);
        g.pose().translate(x, y, 0f);
        g.pose().scale(SCALE, SCALE, 1f);

        long now = System.currentTimeMillis();
        drawOutline(g, look.glyph, MASK_X, 0);
        if (look.live()) {
            drawMic(g, look, 0, 0, now);
        } else {
            drawMask(g, look.glyph, MASK_X, 0, GeneratorFuelUiTheme.GHOST);
            drawSlash(g, look, MASK_X, 0, now);
        }
        g.pose().popPose();
    }

    /** Центр по вертикалі, на якому стоїть панель. Міняй тут, якщо треба посунути вгору/вниз. */
    private static int centerY(Minecraft mc) {
        return mc.getWindow().getGuiScaledHeight() / 2;
    }

    private static Look lookFor(VoiceIndicatorState.Own own) {
        return switch (own) {
            case TALK -> Look.TALK;
            case WHISPER -> Look.WHISPER;
            case MUTED -> Look.MUTED;
            case DEAFENED -> Look.DEAFENED;
            case DISCONNECTED -> Look.DISCONNECTED;
            case NONE -> null;
        };
    }

    // ── Живий мікрофон ───────────────────────────────────────────────────

    private static void drawMic(GuiGraphics g, Look look, int gx, int gy, long now) {
        int ox = gx + MASK_X;
        int filledRows = Math.max(1, Math.round(level * CAPSULE_ROWS));
        int fillCol = GeneratorFuelUiTheme.mix(look.color, GeneratorFuelUiTheme.HIGHLIGHT, level * 0.8f);

        for (int r = 0; r < MIC.length; r++) {
            String row = MIC[r];
            for (int c = 0; c < row.length(); c++) {
                if (row.charAt(c) != 'X') continue;
                boolean capsule = r < CAPSULE_ROWS && c >= CAPSULE_C0 && c <= CAPSULE_C1;
                int color;
                if (capsule) {
                    color = (CAPSULE_ROWS - 1 - r) < filledRows ? fillCol : STEEL_EMPTY;   // шкала знизу вгору
                } else {
                    color = look.color;                                                       // підставка/дуга
                }
                g.fill(ox + c, gy + r, ox + c + 1, gy + r + 1, color);
            }
        }

        // Звукові хвилі: ")" праворуч і "(" ліворуч. 1-ша — від помірного голосу, 2-га — від гучного.
        float a1 = UiAnim.clamp01((level - 0.10f) / 0.25f);
        float a2 = look.waves >= 2 ? UiAnim.clamp01((level - 0.45f) / 0.25f) : 0f;
        int kick = (level > 0.75f && (now / 90L) % 2L == 0L) ? 1 : 0;                       // пульс на піку
        if (a1 > 0f) drawWave(g, gx + MASK_X + 11, gy + 1, 5, 1, GeneratorFuelUiTheme.alpha(fillCol, a1));
        if (a1 > 0f) drawWave(g, gx + MASK_X - 1, gy + 1, 5, -1, GeneratorFuelUiTheme.alpha(fillCol, a1));
        if (a2 > 0f) drawWave(g, gx + MASK_X + 13 + kick, gy, 7, 1, GeneratorFuelUiTheme.alpha(fillCol, a2));
        if (a2 > 0f) drawWave(g, gx + MASK_X - 3 - kick, gy, 7, -1, GeneratorFuelUiTheme.alpha(fillCol, a2));
    }

    /** Дужка висотою {@code h}: кінці на {@code x}, середина на {@code x + dir} (dir=1 — ")", dir=-1 — "("). */
    private static void drawWave(GuiGraphics g, int x, int y, int h, int dir, int argb) {
        for (int i = 0; i < h; i++) {
            int px = (i == 0 || i == h - 1) ? x : x + dir;
            g.fill(px, y + i, px + 1, y + i + 1, argb);
        }
    }

    /** Темний 1-піксельний контур навколо маски — замість панелі, щоб іконка читалась на будь-якому тлі. */
    private static void drawOutline(GuiGraphics g, String[] mask, int x, int y) {
        int c = 0xE0050709;
        for (int r = 0; r < mask.length; r++) {
            String row = mask[r];
            for (int col = 0; col < row.length(); col++) {
                if (row.charAt(col) != 'X') continue;
                int px = x + col, py = y + r;
                g.fill(px - 1, py, px + 2, py + 1, c);
                g.fill(px, py - 1, px + 1, py + 2, c);
            }
        }
    }

    // ── Вимкнені стани ───────────────────────────────────────────────────

    private static void drawMask(GuiGraphics g, String[] mask, int x, int y, int color) {
        for (int r = 0; r < mask.length; r++) {
            String row = mask[r];
            for (int c = 0; c < row.length(); c++) {
                if (row.charAt(c) == 'X') g.fill(x + c, y + r, x + c + 1, y + r + 1, color);
            }
        }
    }

    /** Діагональне перекреслення 11×11 з темним контуром; бурштинове блимає. */
    private static void drawSlash(GuiGraphics g, Look look, int x, int y, long now) {
        float a = look.blink ? 0.55f + 0.45f * (float) Math.sin(now / 250.0) : 1f;
        int color = GeneratorFuelUiTheme.alpha(look.color, a);
        int outline = GeneratorFuelUiTheme.alpha(GeneratorFuelUiTheme.INSET_BG, a);
        for (int i = 0; i < 11; i++) {
            g.fill(x + i + 1, y + i, x + i + 2, y + i + 1, outline);
            g.fill(x + i - 1, y + i, x + i, y + i + 1, outline);
            g.fill(x + i, y + i, x + i + 1, y + i + 1, color);
        }
    }

    /** Вихід зі світу: стан голосу не має переходити в наступну сесію. */
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        VoiceIndicatorState.reset();
        appear = 0f;
        level = 0f;
        shown = null;
        lastFrameNanos[0] = 0L;
    }
}