package com.log_to_kot.maniacmod.client.screen.voice;

import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme.alpha;
import static com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme.mix;

/**
 * Віджети голосових меню мода: кнопка, перемикач, цикл-кнопка, повзунок, картка пристрою, рядок гучності.
 *
 * Усі малюються в стилі мода ({@link ManiacUiTheme}/{@link GeneratorFuelUiTheme}) і мають однакову
 * анімацію: поява зі зсувом і затримкою (каскад), плавне наведення, спалах при натисканні. Логіку
 * (значення, збереження в конфіг SVC) віджети самі не знають — лише викликають передані замикання.
 *
 * Кожен віджет вміє обрізатися «вікном прокрутки» ({@link VWidget#setClip}): поза вікном він не
 * реагує на мишу, а малює його сам екран під ножицями.
 */
public final class VoiceWidgets {

    /** Загальна прозорість екрана (поява/закриття) — виставляє екран перед малюванням віджетів. */
    static float master = 1f;

    private static final int ACCENT = GeneratorFuelUiTheme.FILL;
    private static final int ROW_BG = 0xFF0B0F13;
    private static final int ROW_BG_HOT = 0xFF171D24;

    private VoiceWidgets() {}

    // ═════════════════════════════════════════════════════════════════════
    //  База
    // ═════════════════════════════════════════════════════════════════════

    public abstract static class VWidget extends AbstractButton {
        protected float hover;
        protected float press;
        /** 0..1 — «розкриття» рядка (для рядків, що ховаються/з'являються). */
        public float show = 1f;
        protected long bornAt = Util.getMillis();
        protected int delayMs;
        protected boolean noIntro;
        protected int clipTop = Integer.MIN_VALUE;
        protected int clipBottom = Integer.MAX_VALUE;
        private long lastNanos;
        private boolean wasHot;
        protected final Font font = Minecraft.getInstance().font;

        protected VWidget(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty());
        }

        public VWidget delay(int ms) {
            this.delayMs = ms;
            return this;
        }

        public VWidget noIntro() {
            this.noIntro = true;
            return this;
        }

        public void setClip(int top, int bottom) {
            this.clipTop = top;
            this.clipBottom = bottom;
        }

        protected boolean inClip(double my) {
            return my >= clipTop && my < clipBottom;
        }

        @Override
        public boolean isMouseOver(double mx, double my) {
            return super.isMouseOver(mx, my) && inClip(my);
        }

        @Override
        protected boolean clicked(double mx, double my) {
            return super.clicked(mx, my) && inClip(my);
        }

        /** Миша над віджетом (з урахуванням вікна прокрутки й неактивності). */
        public boolean hot(int mx, int my) {
            return active && visible && mx >= getX() && mx < getX() + width
                    && my >= getY() && my < getY() + height && inClip(my);
        }

        @Override
        public void playDownSound(SoundManager sm) {
            // власний звук (UiAnim.playClick) — ванільний клік не потрібен
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }

        @Override
        public final void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            long now = System.nanoTime();
            float dt = lastNanos == 0L ? 0f : Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
            lastNanos = now;

            boolean hot = hot(mx, my);
            hover = UiAnim.approach(hover, hot ? 1f : 0f, 16f, dt);
            press = UiAnim.approach(press, 0f, 7f, dt);
            if (hot && !wasHot) UiAnim.playMenuHover();
            wasHot = hot;
            tickAnim(dt);

            float ap = noIntro ? 1f : Mth.clamp((Util.getMillis() - bornAt - delayMs) / 300f, 0f, 1f);
            float a = ap * master * Mth.clamp(show, 0f, 1f);
            if (a < 0.06f) return;
            int ox = Math.round((1f - UiAnim.easeOutCubic(ap)) * -18f);
            paint(g, mx, my, getX() + ox, getY(), a);
        }

        protected void tickAnim(float dt) {}

        protected abstract void paint(GuiGraphics g, int mx, int my, int x, int y, float a);

        protected void flash() {
            press = 1f;
            UiAnim.playClick();
        }

        // ── Спільний фон рядка ──────────────────────────────────────────

        protected void paintRow(GuiGraphics g, int x, int y, float a) {
            g.fill(x, y, x + width, y + height, alpha(mix(ROW_BG, ROW_BG_HOT, hover), a));
            ManiacUiTheme.border1px(g, x, y, width, height,
                    alpha(mix(ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT, hover * 0.8f), a));
            int aw = 2 + Math.round(hover * 3f);
            g.fill(x + 1, y + 1, x + 1 + aw, y + height - 1, alpha(ACCENT, a * (0.35f + 0.65f * hover)));
            if (hover > 0.02f) UiFx.glow(g, x, y, width, height, 0xC2C9D6, hover * 0.5f * a, 2);
            if (press > 0.02f) g.fill(x + 1, y + 1, x + width - 1, y + height - 1, alpha(0xFFFFFFFF, press * 0.14f * a));
        }

        protected int textColor(float a) {
            if (!active) return alpha(ManiacUiTheme.TEXT_DIM, a * 0.7f);
            return alpha(mix(ManiacUiTheme.TEXT_BODY, ManiacUiTheme.TEXT_TITLE, hover), a);
        }

        protected int textY(int y) {
            return y + (height - font.lineHeight) / 2 + 1;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Кнопка
    // ═════════════════════════════════════════════════════════════════════

    public static class VButton extends VWidget {
        private final Supplier<Component> label;
        private final Runnable action;
        private BooleanSupplier live;

        public VButton(int x, int y, int w, int h, Supplier<Component> label, Runnable action) {
            super(x, y, w, h);
            this.label = label;
            this.action = action;
        }

        public VButton(int x, int y, int w, int h, Component label, Runnable action) {
            this(x, y, w, h, () -> label, action);
        }

        /** Пульсуюча червона крапка справа, поки умова істинна (тест мікрофона, запис). */
        public VButton live(BooleanSupplier live) {
            this.live = live;
            return this;
        }

        @Override
        public void onPress() {
            if (!active) return;
            flash();
            action.run();
        }

        @Override
        protected void paint(GuiGraphics g, int mx, int my, int x, int y, float a) {
            paintRow(g, x, y, a);
            Component text = label.get();
            g.drawCenteredString(font, text, x + width / 2 + 2, textY(y), textColor(a));
            if (live != null && live.getAsBoolean()) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(Util.getMillis() / 180.0);
                int cy = y + height / 2;
                g.fill(x + width - 14, cy - 3, x + width - 8, cy + 3,
                        alpha(GeneratorFuelUiTheme.FILL_DANGER, a * (0.55f + 0.45f * pulse)));
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Перемикач (switch)
    // ═════════════════════════════════════════════════════════════════════

    public static class VToggle extends VWidget {
        private final Component label;
        private final BooleanSupplier state;
        private final Runnable toggle;
        private Supplier<String> extra;
        private float knob;
        private boolean firstFrame = true;

        public VToggle(int x, int y, int w, int h, Component label, BooleanSupplier state, Runnable toggle) {
            super(x, y, w, h);
            this.label = label;
            this.state = state;
            this.toggle = toggle;
        }

        /** Додатковий текст між назвою і перемикачем (наприклад, час запису). */
        public VToggle extra(Supplier<String> extra) {
            this.extra = extra;
            return this;
        }

        @Override
        public void onPress() {
            if (!active) return;
            flash();
            toggle.run();
        }

        @Override
        protected void tickAnim(float dt) {
            float target = state.getAsBoolean() ? 1f : 0f;
            if (firstFrame) {
                knob = target;
                firstFrame = false;
            }
            knob = UiAnim.approach(knob, target, 18f, dt);
        }

        @Override
        protected void paint(GuiGraphics g, int mx, int my, int x, int y, float a) {
            paintRow(g, x, y, a);
            g.drawString(font, label, x + 12, textY(y), textColor(a), false);

            int tw = 26, th = 10;
            int tx = x + width - tw - 10;
            int ty = y + (height - th) / 2;
            boolean on = state.getAsBoolean();
            int trackCol = mix(0xFF1A2027, 0xFF6F7885, knob);
            if (!active) trackCol = mix(trackCol, 0xFF0B0F13, 0.5f);
            g.fill(tx, ty, tx + tw, ty + th, alpha(trackCol, a));
            ManiacUiTheme.border1px(g, tx, ty, tw, th, alpha(mix(ManiacUiTheme.BORDER, ACCENT, knob), a));
            int kx = tx + 1 + Math.round(knob * (tw - th));
            g.fill(kx + 1, ty + 1, kx + th - 1, ty + th - 1,
                    alpha(mix(0xFF59616D, GeneratorFuelUiTheme.HIGHLIGHT, knob), a));

            String stateText = Component.translatable(on ? "maniacvoice.on" : "maniacvoice.off").getString();
            int sx = tx - 8 - font.width(stateText);
            g.drawString(font, stateText, sx, textY(y),
                    alpha(mix(ManiacUiTheme.TEXT_DIM, ManiacUiTheme.TEXT_ACCENT, knob), a), false);

            if (extra != null) {
                String ex = extra.get();
                if (!ex.isEmpty()) {
                    g.drawString(font, ex, sx - 8 - font.width(ex), textY(y),
                            alpha(GeneratorFuelUiTheme.FILL_DANGER, a), false);
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Цикл-кнопка: «Назва ........ Значення ›»
    // ═════════════════════════════════════════════════════════════════════

    public static class VCycle extends VWidget {
        private final Component label;
        private final Supplier<Component> value;
        private final Runnable next;

        public VCycle(int x, int y, int w, int h, Component label, Supplier<Component> value, Runnable next) {
            super(x, y, w, h);
            this.label = label;
            this.value = value;
            this.next = next;
        }

        @Override
        public void onPress() {
            if (!active) return;
            flash();
            next.run();
        }

        @Override
        protected void paint(GuiGraphics g, int mx, int my, int x, int y, float a) {
            paintRow(g, x, y, a);
            g.drawString(font, label, x + 12, textY(y), textColor(a), false);
            Component v = value.get();
            int chevronX = x + width - 14 + Math.round(hover * 2f);
            g.drawString(font, "›", chevronX, textY(y), alpha(mix(ManiacUiTheme.TEXT_DIM, ACCENT, hover), a), false);
            g.drawString(font, v, chevronX - 6 - font.width(v), textY(y),
                    alpha(active ? ManiacUiTheme.TEXT_ACCENT : ManiacUiTheme.TEXT_DIM, a), false);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Повзунок
    // ═════════════════════════════════════════════════════════════════════

    public static class VSlider extends VWidget {
        private final Component label;
        private final Function<Double, String> format;
        private final DoubleConsumer apply;
        private double value;
        private double shown;
        private double lastApplied;
        private boolean dragging;
        private DoubleSupplier level;
        private float levelShown;
        private Predicate<Double> warn;

        public VSlider(int x, int y, int w, int h, Component label, double initial,
                       Function<Double, String> format, DoubleConsumer apply) {
            super(x, y, w, h);
            this.label = label;
            this.value = initial;
            this.shown = initial;
            this.lastApplied = initial;
            this.format = format;
            this.apply = apply;
        }

        /** Живий рівень сигналу (0..1) підсвічується всередині треку — для порога активації. */
        public VSlider level(DoubleSupplier level) {
            this.level = level;
            return this;
        }

        /** Коли значення «небезпечне» (наприклад, поріг майже на максимумі) — заливка червоніє. */
        public VSlider warn(Predicate<Double> warn) {
            this.warn = warn;
            return this;
        }

        private int trackX() { return getX() + 12; }
        private int trackW() { return width - 24; }

        private void setFromMouse(double mx) {
            value = Mth.clamp((mx - trackX()) / trackW(), 0D, 1D);
        }

        private void commit() {
            if (value == lastApplied) return;
            lastApplied = value;
            apply.accept(value);
        }

        @Override
        public void onPress() {}

        @Override
        public void onClick(double mx, double my) {
            dragging = true;
            setFromMouse(mx);
            commit();
            press = 0.6f;
        }

        @Override
        protected void onDrag(double mx, double my, double dx, double dy) {
            if (!dragging) return;
            setFromMouse(mx);
            if (value >= 1D || value <= 0D) commit();
        }

        @Override
        public void onRelease(double mx, double my) {
            if (dragging) {
                dragging = false;
                commit();
            }
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (!active) return false;
            if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
                value = Mth.clamp(value + (key == GLFW.GLFW_KEY_LEFT ? -0.01D : 0.01D), 0D, 1D);
                commit();
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }

        @Override
        protected void tickAnim(float dt) {
            shown = UiAnim.approach((float) shown, (float) value, 34f, dt);
            if (level != null) {
                levelShown = UiAnim.approach(levelShown, (float) Mth.clamp(level.getAsDouble(), 0D, 1D), 28f, dt);
            }
        }

        @Override
        protected void paint(GuiGraphics g, int mx, int my, int x, int y, float a) {
            paintRow(g, x, y, a);
            int textCol = textColor(a);
            g.drawString(font, label, x + 12, y + 5, textCol, false);
            String val = format.apply(value);
            boolean danger = warn != null && warn.test(value);
            g.drawString(font, val, x + width - 12 - font.width(val), y + 5,
                    alpha(danger ? GeneratorFuelUiTheme.FILL_DANGER : ManiacUiTheme.TEXT_ACCENT, a), false);

            int tx = x + 12, tw = trackW(), th = 6, ty = y + height - 11;
            g.fill(tx, ty, tx + tw, ty + th, alpha(GeneratorFuelUiTheme.INSET_BG, a));
            GeneratorFuelUiTheme.bevelBorderInset(g, tx, ty, tw, th);

            int fillW = Math.round((float) shown * (tw - 2));
            int fillCol = danger ? GeneratorFuelUiTheme.FILL_DANGER : ACCENT;
            if (level != null && levelShown > 0.005f) {
                int lw = Math.round(levelShown * (tw - 2));
                g.fill(tx + 1, ty + 1, tx + 1 + lw, ty + th - 1, alpha(0xFF3E8F63, a * 0.9f));
            }
            if (fillW > 0) {
                g.fill(tx + 1, ty + 1, tx + 1 + fillW, ty + th - 1, alpha(fillCol, a * (level != null ? 0.45f : 0.9f)));
                UiFx.barGlint(g, tx + 1, ty + 1, fillW, th - 2, 2400L, 0f);
            }
            int hx = tx + 1 + fillW;
            int hh = 10 + Math.round(hover * 2f);
            g.fill(hx - 1, ty + th / 2 - hh / 2, hx + 2, ty + th / 2 + hh / 2,
                    alpha(mix(0xFFB9C0CC, GeneratorFuelUiTheme.HIGHLIGHT, hover), a));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Індикатор рівня мікрофона (під час тесту)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Живий рівень голосу під час тесту мікрофона: шкала 0..1 (за дБ, як у SVC), мітка порога активації,
     * «пік» що повільно спадає, цифра в дБ і статус — чи перебиває голос поріг (лише в режимі «голосом»).
     * Не інтерактивний.
     */
    public static class VMeter extends VWidget {
        private static final int GREEN = 0xFF4FC783;
        private static final int BELOW = 0xFF6B7482;
        private static final long HIT_HOLD_MS = 250L;
        private static final long PEAK_HOLD_MS = 700L;

        private final Component label;
        private final DoubleSupplier level;
        private final DoubleSupplier threshold;
        private final BooleanSupplier voiceMode;
        private final Function<Double, String> dbText;
        private float shown;
        private float peak;
        private float hit;
        private long peakAt;
        private long hitAt;

        public VMeter(int x, int y, int w, int h, Component label, DoubleSupplier level,
                      DoubleSupplier threshold, BooleanSupplier voiceMode, Function<Double, String> dbText) {
            super(x, y, w, h);
            this.label = label;
            this.level = level;
            this.threshold = threshold;
            this.voiceMode = voiceMode;
            this.dbText = dbText;
        }

        @Override
        public boolean hot(int mx, int my) {
            return false;
        }

        @Override
        public void onPress() {}

        @Override
        public void onClick(double mx, double my) {}

        private boolean passes() {
            return level.getAsDouble() >= threshold.getAsDouble() && level.getAsDouble() > 0.005D;
        }

        @Override
        protected void tickAnim(float dt) {
            long now = Util.getMillis();
            float lv = (float) Mth.clamp(level.getAsDouble(), 0D, 1D);
            shown = UiAnim.approach(shown, lv, 28f, dt);
            if (shown >= peak) {
                peak = shown;
                peakAt = now;
            } else if (now - peakAt > PEAK_HOLD_MS) {
                peak = Math.max(shown, peak - dt * 0.6f);
            }
            if (voiceMode.getAsBoolean() && passes()) {
                hitAt = now;
            }
            hit = UiAnim.approach(hit, now - hitAt < HIT_HOLD_MS ? 1f : 0f, 18f, dt);
        }

        @Override
        protected void paint(GuiGraphics g, int mx, int my, int x, int y, float a) {
            paintRow(g, x, y, a);
            boolean voice = voiceMode.getAsBoolean();
            float thr = (float) Mth.clamp(threshold.getAsDouble(), 0D, 1D);

            g.drawString(font, label, x + 12, y + 5, textColor(a), false);
            String db = shown < 0.005f ? "—" : dbText.apply((double) shown) + " dB";
            g.drawString(font, db, x + width - 12 - font.width(db), y + 5,
                    alpha(ManiacUiTheme.TEXT_ACCENT, a), false);

            int tx = x + 12, tw = width - 24, th = 8, ty = y + 18;
            g.fill(tx, ty, tx + tw, ty + th, alpha(GeneratorFuelUiTheme.INSET_BG, a));
            GeneratorFuelUiTheme.bevelBorderInset(g, tx, ty, tw, th);

            int inner = tw - 2;
            int fillW = Math.round(shown * inner);
            if (voice) {
                int thrW = Math.round(thr * inner);
                int below = Math.min(fillW, thrW);
                if (below > 0) {
                    g.fill(tx + 1, ty + 1, tx + 1 + below, ty + th - 1, alpha(BELOW, a * 0.9f));
                }
                if (fillW > thrW) {
                    g.fill(tx + 1 + thrW, ty + 1, tx + 1 + fillW, ty + th - 1,
                            alpha(mix(GREEN, GeneratorFuelUiTheme.HIGHLIGHT, hit * 0.25f), a));
                }
            } else if (fillW > 0) {
                g.fill(tx + 1, ty + 1, tx + 1 + fillW, ty + th - 1, alpha(GREEN, a * 0.9f));
            }

            // Пік, що повільно спадає.
            if (peak > 0.01f) {
                int px = tx + 1 + Math.min(inner - 1, Math.round(peak * inner));
                g.fill(px, ty + 1, px + 1, ty + th - 1, alpha(GeneratorFuelUiTheme.HIGHLIGHT, a * 0.8f));
            }
            // Мітка порога.
            if (voice) {
                int mxp = tx + 1 + Math.min(inner - 1, Math.round(thr * inner));
                g.fill(mxp, ty - 2, mxp + 1, ty + th + 2, alpha(GeneratorFuelUiTheme.FILL_WARN, a));
            }

            // Статус.
            String status;
            int col;
            if (voice) {
                boolean on = hit > 0.5f;
                status = Component.translatable(on ? "maniacvoice.meter.hit" : "maniacvoice.meter.below").getString();
                col = on ? GREEN : GeneratorFuelUiTheme.GHOST;
            } else {
                status = Component.translatable("maniacvoice.meter.ptt").getString();
                col = GeneratorFuelUiTheme.GHOST;
            }
            g.drawString(font, status, x + 12, y + height - 12, alpha(col, a), false);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Картка пристрою
    // ═════════════════════════════════════════════════════════════════════

    public static class VDeviceCard extends VWidget {
        private final String name;
        private final BooleanSupplier selected;
        private final Runnable select;
        private float sel;

        public VDeviceCard(int x, int y, int w, int h, String name, BooleanSupplier selected, Runnable select) {
            super(x, y, w, h);
            this.name = name;
            this.selected = selected;
            this.select = select;
        }

        @Override
        public void onPress() {
            if (selected.getAsBoolean()) return;
            flash();
            select.run();
        }

        @Override
        protected void tickAnim(float dt) {
            sel = UiAnim.approach(sel, selected.getAsBoolean() ? 1f : 0f, 14f, dt);
        }

        @Override
        protected void paint(GuiGraphics g, int mx, int my, int x, int y, float a) {
            paintRow(g, x, y, a);
            if (sel > 0.02f) {
                g.fill(x + 1, y + 1, x + width - 1, y + height - 1, alpha(0xFFC2C9D6, sel * 0.10f * a));
                ManiacUiTheme.border1px(g, x, y, width, height, alpha(ACCENT, sel * 0.9f * a));
            }
            int cx = x + 16, cy = y + height / 2;
            ManiacUiTheme.border1px(g, cx - 5, cy - 5, 10, 10, alpha(mix(ManiacUiTheme.BORDER_BRIGHT, ACCENT, sel), a));
            if (sel > 0.02f) {
                int s = Math.round(2 + 2 * sel);
                g.fill(cx - s, cy - s, cx + s, cy + s, alpha(GeneratorFuelUiTheme.HIGHLIGHT, sel * a));
            }
            int textX = x + 32;
            float space = width - 32 - 12;
            float scale = Math.min(space / Math.max(1, font.width(name)), 1f);
            g.pose().pushPose();
            g.pose().translate(textX, cy - (font.lineHeight * scale) / 2f, 0f);
            g.pose().scale(scale, scale, 1f);
            g.drawString(font, name, 0, 0, alpha(mix(ManiacUiTheme.TEXT_BODY, ManiacUiTheme.TEXT_TITLE, Math.max(hover, sel)), a), false);
            g.pose().popPose();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Рядок гучності гравця / категорії
    // ═════════════════════════════════════════════════════════════════════

    public static class VVolumeRow extends VWidget {
        private static final float MAX = 4F;
        private final SvcBridge.VolumeRow data;
        private double value;
        private double shown;
        private double lastApplied;
        private boolean dragging;

        public VVolumeRow(int x, int y, int w, int h, SvcBridge.VolumeRow data) {
            super(x, y, w, h);
            this.data = data;
            this.value = Mth.clamp(data.get().getAsDouble() / MAX, 0D, 1D);
            this.shown = value;
            this.lastApplied = value;
        }

        public String description() {
            return data.description();
        }

        private int sliderX() { return getX() + Math.max(width / 2 - 10, 120); }
        private int sliderW() { return getX() + width - 12 - sliderX(); }

        private void setFromMouse(double mx) {
            value = Mth.clamp((mx - sliderX()) / Math.max(1, sliderW()), 0D, 1D);
        }

        private void commit() {
            if (value == lastApplied) return;
            lastApplied = value;
            data.set().accept(value * MAX);
        }

        @Override
        public void onPress() {}

        @Override
        public void onClick(double mx, double my) {
            dragging = mx >= sliderX() - 4;
            if (dragging) {
                setFromMouse(mx);
                commit();
                press = 0.6f;
            }
        }

        @Override
        protected void onDrag(double mx, double my, double dx, double dy) {
            if (!dragging) return;
            setFromMouse(mx);
            if (value >= 1D || value <= 0D) commit();
        }

        @Override
        public void onRelease(double mx, double my) {
            if (dragging) {
                dragging = false;
                commit();
            }
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
                value = Mth.clamp(value + (key == GLFW.GLFW_KEY_LEFT ? -0.0125D : 0.0125D), 0D, 1D);
                commit();
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }

        @Override
        protected void tickAnim(float dt) {
            shown = UiAnim.approach((float) shown, (float) value, 34f, dt);
        }

        private String valueText() {
            if (value <= 0D) return Component.translatable("maniacvoice.muted").getString();
            long amp = Math.round(value * MAX * 100D - 100D);
            return (amp > 0 ? "+" : "") + amp + "%";
        }

        @Override
        protected void paint(GuiGraphics g, int mx, int my, int x, int y, float a) {
            paintRow(g, x, y, a);
            int size = 24;
            int ix = x + 10, iy = y + (height - size) / 2;
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1f, 1f, 1f, a);
            if (data.skin()) {
                g.blit(data.icon(), ix, iy, size, size, 8, 8, 8, 8, 64, 64);
                g.blit(data.icon(), ix, iy, size, size, 40, 8, 8, 8, 64, 64);
            } else {
                g.blit(data.icon(), ix, iy, size, size, 0f, 0f, 16, 16, 16, 16);
            }
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();

            int nameX = ix + size + 8;
            int nameMax = sliderX() - nameX - 8;
            String name = font.plainSubstrByWidth(data.name(), nameMax);
            g.drawString(font, name, nameX, textY(y), alpha(mix(ManiacUiTheme.TEXT_BODY, ManiacUiTheme.TEXT_TITLE, hover), a), false);

            int sx = sliderX(), sw = sliderW();
            String vt = valueText();
            boolean muted = value <= 0D;
            g.drawString(font, vt, sx + sw - font.width(vt), y + 6,
                    alpha(muted ? GeneratorFuelUiTheme.FILL_DANGER : ManiacUiTheme.TEXT_ACCENT, a), false);
            int ty = y + height - 13, th = 6;
            g.fill(sx, ty, sx + sw, ty + th, alpha(GeneratorFuelUiTheme.INSET_BG, a));
            GeneratorFuelUiTheme.bevelBorderInset(g, sx, ty, sw, th);
            int fw = Math.round((float) shown * (sw - 2));
            if (fw > 0) {
                g.fill(sx + 1, ty + 1, sx + 1 + fw, ty + th - 1, alpha(ACCENT, a * 0.9f));
                UiFx.barGlint(g, sx + 1, ty + 1, fw, th - 2, 2400L, 0f);
            }
            // позначка «100%» — де звук без підсилення
            int mark = sx + 1 + Math.round(0.25f * (sw - 2));
            g.fill(mark, ty - 2, mark + 1, ty + th + 2, alpha(0xFF8E97A5, a * 0.8f));
            int hx = sx + 1 + fw;
            g.fill(hx - 1, ty - 2, hx + 2, ty + th + 2, alpha(mix(0xFFB9C0CC, GeneratorFuelUiTheme.HIGHLIGHT, hover), a));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Допоміжні малюнки
    // ═════════════════════════════════════════════════════════════════════

    /** Еквалайзер-прикраса: стовпчики «дихають», коли активний, і лягають, коли ні. */
    public static void drawEqualizer(GuiGraphics g, int x, int y, int w, int h, float energy, int rgb, float a) {
        int bars = Math.max(3, w / 5);
        long t = Util.getMillis();
        for (int i = 0; i < bars; i++) {
            float wave = 0.5f + 0.5f * (float) Math.sin(t / (210.0 + i * 37.0) + i * 1.7);
            float k = 0.12f + energy * (0.20f + 0.68f * wave);
            int bh = Math.max(2, Math.round(h * k));
            int bx = x + i * 5;
            g.fill(bx, y + h - bh, bx + 3, y + h, alpha(0xFF000000 | (rgb & 0xFFFFFF), a * (0.45f + 0.55f * k)));
        }
    }

    /** Тонкий скролбар справа від вікна прокрутки. */
    public static void drawScrollbar(GuiGraphics g, int x, int top, int bottom, float scroll, float maxScroll, float a) {
        if (maxScroll <= 0f) return;
        int viewH = bottom - top;
        float ratio = viewH / (viewH + maxScroll);
        int barH = Math.max(14, Math.round(viewH * ratio));
        int barY = top + Math.round((viewH - barH) * (scroll / maxScroll));
        g.fill(x, top, x + 2, bottom, alpha(0xFF12161E, a));
        g.fill(x, barY, x + 2, barY + barH, alpha(0xFF8E97A5, a));
    }

}
